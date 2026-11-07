import { spawn } from "node:child_process"
import { access, mkdtemp, readdir, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { writeFile } from "node:fs/promises"
import { jarSelectorMatches } from "../jar-match.ts"
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
  const jars = await findJars(directory)
  const plugins: { abs: string; rel: string; base: string }[] = []
  for (const jar of jars) {
    const names = zipEntryNames(await readFile(jar))
    if (!isPluginJar(names)) continue
    const rel = path.relative(directory, jar).replace(/\\/g, "/")
    plugins.push({ abs: jar, rel, base: path.basename(jar) })
  }
  if (plugins.length === 0) {
    throw new Error(`${plugin.name} produced no plugin jars`)
  }
  if (!plugin.jar) {
    if (plugins.length === 1) return plugins[0].abs
    const listed = plugins.map((jar) => jar.rel).join(", ")
    throw new Error(`${plugin.name} produced ${plugins.length} plugin jars; set jar: (name or /regex/): ${listed}`)
  }
  // Match jar: against repo-relative path or basename (exact or /regex/).
  const hits = plugins.filter(
    (jar) => jarSelectorMatches(plugin.jar!, jar.rel) || jarSelectorMatches(plugin.jar!, jar.base),
  )
  if (hits.length === 1) return hits[0].abs
  if (hits.length === 0) {
    throw new Error(
      `${plugin.name} has no jar matching ${plugin.jar}; jars: ${plugins.map((jar) => jar.rel).join(", ")}`,
    )
  }
  throw new Error(
    `${plugin.name} jar ${plugin.jar} matched ${hits.length} files: ${hits.map((jar) => jar.rel).join(", ")}`,
  )
}

/**
 * RoseStacker (and similar Rosewood plugins) require private maven-public credentials
 * for third-party compileOnly hooks. Without them Gradle 401s and the build dies.
 * Strip that repo, drop the private-only compileOnly lines, and stub NPCsHook so the
 * rest of the plugin still compiles from public deps alone.
 */
async function softenRosewoodPrivateMaven(directory: string): Promise<void> {
  const rootGradle = path.join(directory, "build.gradle")
  if (!(await exists(rootGradle))) return
  const root = await readFile(rootGradle, "utf8")
  if (!root.includes("repo.rosewooddev.io/repository/maven-public")) return
  const stripped = root.replace(
    /\n\s*maven\s*\{\s*\n\s*url\s*=\s*['"]https:\/\/repo\.rosewooddev\.io\/repository\/maven-public['"][\s\S]*?\n\s*\}\n/,
    "\n        // plugin-lock: dropped private rosewood maven-public (no credentials)\n",
  )
  if (stripped === root) {
    throw new Error("could not strip rosewood maven-public credentials block from build.gradle")
  }
  await writeFile(rootGradle, stripped)

  const pluginGradle = path.join(directory, "Plugin", "build.gradle")
  if (await exists(pluginGradle)) {
    let plugin = await readFile(pluginGradle, "utf8")
    // These coordinates live only on the private mirror.
    const drop = [
      /[ \t]*compileOnly\s+['"]com\.songoda:epicbosses:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]net\.citizensnpcs:citizens:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]com\.nisovin:shopkeepers:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]com\.magmaguy:elitemobs:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]simplepets\.brainsnyder:simplepets:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]com\.jacob_vejvoda:infernalmobs:[^'"]+['"]\s*\n/g,
      /[ \t]*compileOnly\s+['"]com\.github\.Bestem0r:VillagerMarket:[^'"]+['"]\s*\n/g,
    ]
    for (const pattern of drop) plugin = plugin.replace(pattern, "")
    await writeFile(pluginGradle, plugin)
  }

  const npcHook = path.join(
    directory,
    "Plugin",
    "src",
    "main",
    "java",
    "dev",
    "rosewood",
    "rosestacker",
    "hook",
    "NPCsHook.java",
  )
  if (await exists(npcHook)) {
    await writeFile(
      npcHook,
      `package dev.rosewood.rosestacker.hook;

import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** plugin-lock stub: no private Rosewood compileOnly APIs; reflection / Bukkit only. */
public class NPCsHook {

    private static final NamespacedKey LEVELLEDMOBS_KEY = new NamespacedKey("levelledmobs", "level");

    public static boolean citizensEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public static boolean shopkeepersEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Shopkeepers");
    }

    public static boolean mythicMobsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    public static boolean epicBossesEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("EpicBosses");
    }

    public static boolean eliteMobsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("EliteMobs");
    }

    public static boolean bossEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Boss");
    }

    public static boolean proCosmeticsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ProCosmetics");
    }

    public static boolean infernalMobsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("InfernalMobs");
    }

    public static boolean simplePetsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("SimplePets");
    }

    public static boolean levelledMobsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("LevelledMobs");
    }

    public static boolean villagerMarketEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("VillagerMarket");
    }

    public static boolean mypetEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MyPet");
    }

    public static boolean anyEnabled() {
        return citizensEnabled()
                || shopkeepersEnabled()
                || mythicMobsEnabled()
                || epicBossesEnabled()
                || eliteMobsEnabled()
                || bossEnabled()
                || proCosmeticsEnabled()
                || infernalMobsEnabled()
                || simplePetsEnabled()
                || levelledMobsEnabled()
                || villagerMarketEnabled()
                || mypetEnabled();
    }

    public static boolean isNPC(LivingEntity entity) {
        if (entity.hasMetadata("Boss_V4") || entity.hasMetadata("PROCOSMETICS_ENTITY")) return true;
        if (levelledMobsEnabled() && entity.getPersistentDataContainer().has(LEVELLEDMOBS_KEY, PersistentDataType.INTEGER)) {
            return true;
        }
        return reflectNpc(entity);
    }

    private static boolean reflectNpc(LivingEntity entity) {
        try {
            if (citizensEnabled()) {
                Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object registry = api.getMethod("getNPCRegistry").invoke(null);
                if (registry != null && Boolean.TRUE.equals(registry.getClass().getMethod("isNPC", org.bukkit.entity.Entity.class).invoke(registry, entity))) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            if (mythicMobsEnabled()) {
                Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                Object inst = mythic.getMethod("inst").invoke(null);
                Object helper = inst.getClass().getMethod("getAPIHelper").invoke(inst);
                if (Boolean.TRUE.equals(helper.getClass().getMethod("isMythicMob", org.bukkit.entity.Entity.class).invoke(helper, entity))) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            if (simplePetsEnabled()) {
                Class<?> pets = Class.forName("simplepets.brainsynder.api.plugin.SimplePets");
                Method isPet = pets.getMethod("isPetEntity", org.bukkit.entity.Entity.class);
                if (Boolean.TRUE.equals(isPet.invoke(null, entity))) return true;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            if (mypetEnabled()) {
                Class<?> api = Class.forName("de.Keyle.MyPet.MyPetApi");
                if (Boolean.TRUE.equals(api.getMethod("isReady").invoke(null))) {
                    Object manager = api.getMethod("getPetManager").invoke(null);
                    if (manager.getClass().getMethod("getPetFromEntity", org.bukkit.entity.Entity.class).invoke(manager, entity) != null) {
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        Plugin infernal = Bukkit.getPluginManager().getPlugin("InfernalMobs");
        if (infernal != null && infernal.isEnabled()) {
            try {
                Method idSearch = infernal.getClass().getMethod("idSearch", java.util.UUID.class);
                Object id = idSearch.invoke(infernal, entity.getUniqueId());
                if (id instanceof Number && ((Number) id).intValue() >= 0) return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    public static void addCustomPlaceholders(LivingEntity entity, StringPlaceholders.Builder placeholders) {
        if (levelledMobsEnabled()) {
            Integer level = entity.getPersistentDataContainer().get(LEVELLEDMOBS_KEY, PersistentDataType.INTEGER);
            placeholders.add("levelledmobs_level", level == null ? 0 : level);
        }
    }
}
`,
    )
  }
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
  // Download always pins a full commit in the lock (`version`); honor that over desired.commit.
  const commit = await resolveCommit(repo, plugin.commit)
  const directory = await mkdtemp(path.join(tmpdir(), "plugin-lock-"))
  try {
    await run("git", ["init"], directory)
    await run("git", ["remote", "add", "origin", repo], directory)
    await run("git", ["fetch", "--depth", "1", "origin", commit], directory)
    await run("git", ["checkout", "--detach", "FETCH_HEAD"], directory)
    await softenRosewoodPrivateMaven(directory)
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
    return {
      filename: path.basename(chosen),
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

/** Lock-time resolve: pin the git commit only. Build happens at download (CI/Linux). */
export async function resolveGit(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const repo = plugin.id ?? ""
  if (!repo.startsWith("https://") && !repo.startsWith("git@")) {
    throw new Error(`${plugin.name} needs an https or git repository url`)
  }
  const commit = await resolveCommit(repo, plugin.commit)
  console.log(`${plugin.name} ${commit} git commit (jar built at download)`)
  return {
    name: plugin.name,
    source: "git",
    version: commit,
    // Stable install name; the built artifact basename may include a moving version.
    filename: `${plugin.name}.jar`,
    url: repo,
    jar: plugin.jar,
    builder: plugin.builder,
    java: plugin.java,
    release: plugin.release,
    task: plugin.task,
  }
}
