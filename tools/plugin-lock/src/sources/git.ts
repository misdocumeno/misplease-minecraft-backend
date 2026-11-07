import { spawn } from "node:child_process"
import { access, mkdtemp, readdir, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { writeFile } from "node:fs/promises"
import { basename } from "../desired.ts"
import { digest } from "../http.ts"
import { chooseJava, declaredJava, ensureJdk, ensureMaven, gradleJavaRange, minecraftJava } from "../jdk.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"
import { normalizeJar, zipEntryNames } from "../zip.ts"

const SKIPPED = new Set([".git", ".gradle", "node_modules"])

function targetInit(release: number): string {
  const kotlin = release === 8 ? "1.8" : String(release)
  return `import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

gradle.projectsLoaded {
    rootProject.allprojects { project ->
        project.plugins.withId("java") {
            project.extensions.getByType(JavaPluginExtension).toolchain.languageVersion.set(JavaLanguageVersion.of(${release}))
        }
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
                kotlinOptions.jvmTarget = "${kotlin}"
            }
        }
    }
}
`
}

function run(command: string, args: string[], cwd: string, env: NodeJS.ProcessEnv = process.env): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: "inherit",
      env,
    })
    child.on("error", reject)
    child.on("exit", (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} ${args.join(" ")} exited ${code}`))
    })
  })
}

async function exists(file: string): Promise<boolean> {
  try {
    await access(file)
    return true
  } catch {
    return false
  }
}

function javaRequest(plugin: DesiredPlugin): { major: number; release?: string } | undefined {
  if (!plugin.java) return undefined
  const version = plugin.java.replace(/^jdk-/, "")
  const match = version.match(/^(\d+)(?:\.|$)/)
  if (!match || Number(match[1]) < 8) {
    throw new Error(`${plugin.name} java must be a major like 17 or a Temurin version like 17.0.20.1+1`)
  }
  return { major: Number(match[1]), release: version.includes(".") ? version : undefined }
}

async function detectBuilder(plugin: DesiredPlugin, directory: string): Promise<{ builder: "gradle" | "maven"; ambiguous: boolean }> {
  const gradle =
    (await exists(path.join(directory, "gradlew"))) ||
    (await exists(path.join(directory, "gradlew.bat"))) ||
    (await exists(path.join(directory, "build.gradle"))) ||
    (await exists(path.join(directory, "build.gradle.kts")))
  const maven = await exists(path.join(directory, "pom.xml"))
  if (gradle && maven) {
    if (plugin.builder === "gradle" || plugin.builder === "maven") return { builder: plugin.builder, ambiguous: true }
    throw new Error(`${plugin.name} has both a Gradle build and a Maven pom, so it needs builder: gradle or builder: maven`)
  }
  if (plugin.builder && plugin.builder !== "gradle" && plugin.builder !== "maven") {
    throw new Error(`${plugin.name} builder must be gradle or maven`)
  }
  if (gradle) return { builder: "gradle", ambiguous: false }
  if (maven) return { builder: "maven", ambiguous: false }
  throw new Error(`${plugin.name} has no Gradle build or Maven pom`)
}

async function buildTexts(directory: string): Promise<string> {
  const parts: string[] = []
  async function walk(dir: string): Promise<void> {
    const entries = await readdir(dir, { withFileTypes: true })
    for (const entry of entries) {
      if (SKIPPED.has(entry.name) || entry.name === "build" || entry.name === "target") continue
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        await walk(full)
        continue
      }
      if (/^(build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?|pom\.xml|gradle\.properties|\.java-version)$/.test(entry.name)) {
        parts.push(await readFile(full, "utf8"))
      }
    }
  }
  await walk(directory)
  return parts.join("\n")
}

async function gradleVersion(directory: string): Promise<string | undefined> {
  const properties = path.join(directory, "gradle", "wrapper", "gradle-wrapper.properties")
  if (!(await exists(properties))) return undefined
  const match = (await readFile(properties, "utf8")).match(/gradle-(\d+\.\d+(?:\.\d+)?)-/)
  return match?.[1]
}

function toolEnv(jdkHome: string, mavenHome?: string): NodeJS.ProcessEnv {
  const bins = [path.join(jdkHome, "bin")]
  if (mavenHome) bins.push(path.join(mavenHome, "bin"))
  const current = process.env.Path ?? process.env.PATH ?? ""
  const joined = `${bins.join(path.delimiter)}${path.delimiter}${current}`
  return { ...process.env, JAVA_HOME: jdkHome, PATH: joined, Path: joined, SOURCE_DATE_EPOCH: "0" }
}

function isPluginJar(names: string[]): boolean {
  return names.some((name) => name === "plugin.yml" || name === "paper-plugin.yml" || name === "velocity-plugin.json")
}

async function findJars(directory: string): Promise<string[]> {
  const found: string[] = []
  async function walk(dir: string): Promise<void> {
    const entries = await readdir(dir, { withFileTypes: true })
    for (const entry of entries) {
      if (SKIPPED.has(entry.name)) continue
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        await walk(full)
        continue
      }
      const lower = entry.name.toLowerCase()
      if (!lower.endsWith(".jar") || lower.includes("sources") || lower.includes("javadoc") || lower.startsWith("original-")) {
        continue
      }
      found.push(full)
    }
  }
  await walk(directory)
  return found
}

async function chooseJar(plugin: DesiredPlugin, directory: string): Promise<string> {
  if (plugin.jar) {
    const relative = plugin.jar.replace(/\\/g, "/")
    if (relative.startsWith("/") || relative.split("/").includes("..")) {
      throw new Error(`${plugin.name} jar path must stay inside the repository`)
    }
    return path.join(directory, relative)
  }
  const jars = await findJars(directory)
  const plugins: string[] = []
  for (const jar of jars) {
    const names = zipEntryNames(await readFile(jar))
    if (isPluginJar(names)) plugins.push(jar)
  }
  const named = plugin.asset ? plugins.filter((jar) => path.basename(jar) === basename(plugin.asset ?? "")) : plugins
  if (named.length === 1) return named[0]
  const listed = (named.length > 0 ? named : jars).map((jar) => path.relative(directory, jar)).join(", ")
  throw new Error(`${plugin.name} produced ${named.length} plugin jars${listed ? `: ${listed}` : ""}`)
}

async function resolveCommit(repo: string, commit: string | undefined): Promise<string> {
  if (commit && /^[0-9a-f]{40}$/i.test(commit)) return commit
  // No pin → tip of the remote default branch (ls-remote HEAD).
  const out = await new Promise<string>((resolve, reject) => {
    const child = spawn("git", ["ls-remote", repo, "HEAD"], {
      stdio: ["ignore", "pipe", "pipe"],
      env: process.env,
    })
    let stdout = ""
    let stderr = ""
    child.stdout.on("data", (chunk: Buffer) => {
      stdout += chunk.toString()
    })
    child.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString()
    })
    child.on("error", reject)
    child.on("close", (code) => {
      if (code === 0) resolve(stdout)
      else reject(new Error(`git ls-remote failed (${code}): ${stderr.trim() || stdout.trim()}`))
    })
  })
  const hash = out.trim().split(/\s+/)[0] ?? ""
  if (!/^[0-9a-f]{40}$/i.test(hash)) {
    throw new Error(`could not resolve HEAD for ${repo}`)
  }
  return hash
}

export async function buildNormalizedJar(plugin: DesiredPlugin): Promise<{
  filename: string
  jar: string
  bytes: Buffer
  builder?: string
  java: string
  release?: string
  commit: string
}> {
  const repo = plugin.id ?? ""
  if (!repo.startsWith("https://") && !repo.startsWith("git@")) {
    throw new Error(`${plugin.name} needs an https or git repository url`)
  }
  const commit = await resolveCommit(repo, plugin.commit)
  const directory = await mkdtemp(path.join(tmpdir(), "plugin-lock-"))
  try {
    await run("git", ["init"], directory)
    await run("git", ["remote", "add", "origin", repo], directory)
    await run("git", ["fetch", "--depth", "1", "origin", commit], directory)
    await run("git", ["checkout", "--detach", "FETCH_HEAD"], directory)
    const { builder, ambiguous } = await detectBuilder(plugin, directory)
    const texts = await buildTexts(directory)
    const declared = declaredJava(texts)
    const wrapper = builder === "gradle" ? await gradleVersion(directory) : undefined
    if (builder === "gradle" && !wrapper) {
      throw new Error(`${plugin.name} has no Gradle wrapper`)
    }
    const explicit = javaRequest(plugin)
    const serverMajor = minecraftJava(plugin.serverMinecraft)
    const projectDeclared = declared.length > 0 || Boolean(explicit && !plugin.release)
    if (!explicit && builder === "maven" && declared.length === 0 && serverMajor === undefined && !plugin.release) {
      throw new Error(`${plugin.name} does not declare a Java version, so it needs java:`)
    }
    const range = wrapper ? gradleJavaRange(wrapper) : undefined
    const requested = explicit
      ? [explicit.major]
      : declared.length > 0
        ? declared
        : builder === "maven" && serverMajor
          ? [serverMajor]
          : []
    const major = explicit?.major ?? chooseJava(requested, range, wrapper)
    const bytecode =
      plugin.release !== undefined
        ? Number(plugin.release)
        : !projectDeclared && serverMajor !== undefined && major > serverMajor
          ? serverMajor
          : undefined
    const jdk = await ensureJdk(major, explicit?.release)
    console.log(
      `${plugin.name} compiling with Temurin ${jdk.release}${bytecode ? `, bytecode ${bytecode}` : ""}`,
    )
    const mavenHome = builder === "maven" && !(await exists(path.join(directory, "mvnw"))) ? await ensureMaven() : undefined
    const env = toolEnv(jdk.home, mavenHome)
    const mavenArgs = ["-B", "package", "-DskipTests", "-Dproject.build.outputTimestamp=0"]
    if (bytecode !== undefined) mavenArgs.push(`-Dmaven.compiler.release=${bytecode}`)
    if (builder === "gradle") {
      const task = plugin.task ?? "build"
      const gradleArgs = [task, "--no-daemon"]
      if (!plugin.task) gradleArgs.push("-x", "test", "-x", "javadoc")
      // Prefer explicit Temurin homes over Foojay init scripts (Gradle 9 settings DSL differs).
      const jdkHomes = new Set<string>([jdk.home])
      if (bytecode !== undefined) {
        const compiler = await ensureJdk(bytecode)
        jdkHomes.add(compiler.home)
        const targetScript = path.join(directory, ".plugin-lock-target.init.gradle")
        await writeFile(targetScript, targetInit(bytecode))
        gradleArgs.push("--init-script", targetScript)
      }
      for (const version of declared) {
        if (version !== major) jdkHomes.add((await ensureJdk(version)).home)
      }
      gradleArgs.push(`-Dorg.gradle.java.installations.paths=${[...jdkHomes].join(",")}`)
      gradleArgs.push("-Dorg.gradle.java.installations.auto-download=false")
      if (process.platform === "win32") {
        await run("cmd.exe", ["/d", "/c", "gradlew.bat", ...gradleArgs], directory, env)
      } else {
        await run("chmod", ["+x", "gradlew"], directory, env)
        await run("./gradlew", gradleArgs, directory, env)
      }
    } else if (await exists(path.join(directory, "mvnw"))) {
      if (process.platform === "win32") {
        await run("cmd.exe", ["/d", "/c", "mvnw.cmd", ...mavenArgs], directory, env)
      } else {
        await run("chmod", ["+x", "mvnw"], directory, env)
        await run("./mvnw", mavenArgs, directory, env)
      }
    } else if (process.platform === "win32") {
      await run("cmd.exe", ["/d", "/c", "mvn.cmd", ...mavenArgs], directory, env)
    } else {
      await run("mvn", mavenArgs, directory, env)
    }
    const chosen = await chooseJar(plugin, directory)
    const bytes = normalizeJar(await readFile(chosen))
    const filename = plugin.asset ? basename(plugin.asset) : path.basename(chosen)
    return {
      filename,
      jar: path.relative(directory, chosen).replace(/\\/g, "/"),
      bytes,
      builder: ambiguous ? builder : undefined,
      // Major only — full Temurin builds (25.0.5+8) are not portable across OS in the lock.
      java: String(major),
      release: bytecode === undefined ? undefined : String(bytecode),
      commit,
    }
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

export async function resolveGit(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const built = await buildNormalizedJar(plugin)
  return {
    name: plugin.name,
    source: "git",
    version: built.commit,
    filename: built.filename,
    url: plugin.id ?? "",
    sha256: digest("sha256", built.bytes),
    task: plugin.task,
    jar: built.jar,
    builder: built.builder,
    java: built.java,
    release: built.release,
  }
}
