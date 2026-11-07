import { createWriteStream } from "node:fs"
import { access, mkdir, mkdtemp, readdir, rm } from "node:fs/promises"
import { homedir, tmpdir } from "node:os"
import path from "node:path"
import { Readable } from "node:stream"
import { pipeline } from "node:stream/promises"
import { spawn } from "node:child_process"

const MAVEN_VERSION = "3.9.9"

function host(): { os: string; arch: string } {
  const os = process.platform === "win32" ? "windows" : process.platform === "darwin" ? "mac" : "linux"
  const arch = process.arch === "arm64" ? "aarch64" : "x64"
  return { os, arch }
}

async function exists(file: string): Promise<boolean> {
  try {
    await access(file)
    return true
  } catch {
    return false
  }
}

function run(command: string, args: string[], cwd: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: "inherit" })
    child.on("error", reject)
    child.on("exit", (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} ${args.join(" ")} exited ${code}`))
    })
  })
}

async function download(url: string, destination: string): Promise<void> {
  const response = await fetch(url, { signal: AbortSignal.timeout(300_000) })
  if (!response.ok || !response.body) throw new Error(`${response.status} for ${url}`)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(destination))
}

interface AdoptiumAsset {
  release_name?: string
  binary?: { package?: { link?: string } }
  binaries?: { package?: { link?: string } }[]
}

async function adoptium(major: number, release: string | undefined): Promise<{ release: string; link: string }> {
  const { os, arch } = host()
  const query = new URLSearchParams({
    architecture: arch,
    image_type: "jdk",
    os,
    vendor: "eclipse",
  })
  const url = release
    ? `https://api.adoptium.net/v3/assets/version/${encodeURIComponent(release.startsWith("jdk-") ? release : `jdk-${release}`)}?${query}`
    : `https://api.adoptium.net/v3/assets/latest/${major}/hotspot?${query}`
  const response = await fetch(url, { signal: AbortSignal.timeout(60_000) })
  if (!response.ok) throw new Error(`${response.status} for ${url}`)
  const body = (await response.json()) as AdoptiumAsset | AdoptiumAsset[]
  const asset = Array.isArray(body) ? body[0] : body
  const link = asset?.binary?.package?.link ?? asset?.binaries?.[0]?.package?.link
  const name = asset?.release_name
  if (!link || !name) throw new Error(`Adoptium has no Temurin ${release ?? major} build for ${os} ${arch}`)
  return { release: name.replace(/^jdk-/, ""), link }
}

export async function ensureJdk(major: number, release?: string): Promise<{ home: string; release: string }> {
  const cachedRelease = release?.replace(/^jdk-/, "")
  const cache = path.join(homedir(), ".cache", "misplease", "jdks")
  if (cachedRelease) {
    const existing = path.join(cache, cachedRelease)
    if (await exists(path.join(existing, "bin", process.platform === "win32" ? "java.exe" : "java"))) {
      return { home: existing, release: cachedRelease }
    }
  }
  // Exact Temurin builds are OS-specific; a Windows-locked 25.0.5+8 often 404s on Linux.
  // Prefer the pin when present, otherwise (or on 404) take latest for that major.
  let asset: { release: string; link: string }
  try {
    asset = await adoptium(major, cachedRelease)
  } catch (error) {
    if (!cachedRelease) throw error
    console.log(`Temurin ${cachedRelease} unavailable for this OS; falling back to latest ${major}`)
    asset = await adoptium(major)
  }
  const home = path.join(cache, asset.release)
  const binary = path.join(home, "bin", process.platform === "win32" ? "java.exe" : "java")
  if (await exists(binary)) return { home, release: asset.release }
  await mkdir(cache, { recursive: true })
  const stage = await mkdtemp(path.join(tmpdir(), "jdk-"))
  try {
    const extension = asset.link.includes(".tar.gz") ? "tar.gz" : "zip"
    const archive = path.join(stage, `jdk.${extension}`)
    await download(asset.link, archive)
    await run("tar", ["-xf", archive, "-C", stage], stage)
    const extracted = (await readdir(stage, { withFileTypes: true })).find((entry) => entry.isDirectory())?.name
    if (!extracted) throw new Error(`Temurin ${asset.release} archive was empty`)
    const unpacked = path.join(stage, extracted)
    if (!(await exists(path.join(unpacked, "bin")))) {
      throw new Error(`Temurin ${asset.release} archive has no bin directory`)
    }
    await rm(home, { recursive: true, force: true })
    const { rename } = await import("node:fs/promises")
    await rename(unpacked, home).catch(async () => {
      const { cp } = await import("node:fs/promises")
      await cp(unpacked, home, { recursive: true })
    })
  } finally {
    await rm(stage, { recursive: true, force: true })
  }
  return { home, release: asset.release }
}

export async function ensureMaven(): Promise<string> {
  const home = path.join(homedir(), ".cache", "misplease", "maven", MAVEN_VERSION)
  const binary = path.join(home, "bin", process.platform === "win32" ? "mvn.cmd" : "mvn")
  if (await exists(binary)) return home
  const { os } = host()
  const extension = os === "windows" ? "zip" : "tar.gz"
  const filename = `apache-maven-${MAVEN_VERSION}-bin.${extension}`
  const url = `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/${filename}`
  await mkdir(path.dirname(home), { recursive: true })
  const stage = await mkdtemp(path.join(tmpdir(), "maven-"))
  try {
    const archive = path.join(stage, filename)
    await download(url, archive)
    await run("tar", ["-xf", archive, "-C", stage], stage)
    const unpacked = path.join(stage, `apache-maven-${MAVEN_VERSION}`)
    await rm(home, { recursive: true, force: true })
    const { rename, cp } = await import("node:fs/promises")
    await rename(unpacked, home).catch(async () => {
      await cp(unpacked, home, { recursive: true })
    })
  } finally {
    await rm(stage, { recursive: true, force: true })
  }
  return home
}

export function declaredJava(text: string): number[] {
  const found: number[] = []
  const add = (value: string | undefined) => {
    if (!value) return
    const major = value.startsWith("1.") ? Number(value.slice(2)) : Number(value)
    if (Number.isInteger(major) && major >= 8 && major <= 30) found.push(major)
  }
  for (const match of text.matchAll(/JavaLanguageVersion\.of\(\s*(\d+)\s*\)/g)) add(match[1])
  for (const match of text.matchAll(/JavaVersion\.VERSION_(\d+)(?:_(\d+))?/g)) {
    add(match[2] ? match[2] : match[1])
  }
  for (const match of text.matchAll(/(?:release|sourceCompatibility|targetCompatibility|jvmToolchain|jvmTarget)\s*(?:=|\.set\()\s*"?(1\.\d+|\d+)"?/g)) {
    add(match[1])
  }
  for (const match of text.matchAll(/JvmTarget\.JVM_(\d+)/g)) {
    add(match[1])
  }
  for (const match of text.matchAll(/<(?:maven\.compiler\.(?:release|source|target)|java\.version|maven\.compiler\.release)>\s*(1\.\d+|\d+)\s*</g)) {
    add(match[1])
  }
  return found
}

export function gradleJavaRange(version: string): { min: number; max: number } {
  const [major = 0, minor = 0] = version.split(".").map(Number)
  if (major >= 9) return { min: 17, max: 25 }
  if (major === 8 && minor >= 10) return { min: 8, max: 23 }
  if (major === 8 && minor >= 5) return { min: 8, max: 21 }
  if (major === 8) return { min: 8, max: 19 }
  if (major === 7 && minor >= 6) return { min: 8, max: 19 }
  if (major === 7 && minor >= 3) return { min: 8, max: 18 }
  if (major === 7) return { min: 8, max: 17 }
  return { min: 8, max: 15 }
}

// Gradle's published maximum is sometimes a JDK its Kotlin DSL cannot run on.
// 8.0 through 8.4 accept Java 21 and then fail while configuring.
function gradleLaunchCeiling(version: string): number {
  const [major = 0, minor = 0] = version.split(".").map(Number)
  if (major >= 9) return 21
  if (major === 8 && minor >= 5) return 21
  if (major === 8) return 17
  if (major === 7 && minor >= 3) return 17
  if (major === 7 || major === 6) return 11
  return 8
}

function newestLts(floor: number, ceiling: number): number {
  for (const lts of [21, 17, 11, 8]) {
    if (lts >= floor && lts <= ceiling) return lts
  }
  return ceiling
}

export function minecraftJava(version: string | undefined): number | undefined {
  const match = version?.match(/^(\d+)\.(\d+)(?:\.(\d+))?/)
  if (!match) return undefined
  const major = Number(match[1])
  const minor = Number(match[2])
  const patch = match[3] ? Number(match[3]) : 0
  if (major >= 26) return 25
  if (major !== 1) return undefined
  if (minor >= 21 || (minor === 20 && patch >= 5)) return 21
  if (minor >= 18) return 17
  if (minor === 17) return 16
  return 8
}

export function chooseJava(declared: number[], range: { min: number; max: number } | undefined, gradleVersion?: string): number {
  const requested = declared.length > 0 ? Math.max(...declared) : undefined
  if (!range) return Math.max(8, requested ?? 17)
  const ceiling = Math.min(range.max, gradleVersion ? gradleLaunchCeiling(gradleVersion) : range.max)
  if (requested === undefined) return newestLts(range.min, ceiling)
  if (requested < range.min) return range.min
  if (requested > ceiling) return ceiling
  return requested
}
