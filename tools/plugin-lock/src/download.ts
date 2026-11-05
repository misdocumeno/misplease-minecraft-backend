import { mkdir, readFile, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"
import { digest, downloadBytes, type HashAlgorithm } from "./http.ts"
import { basename } from "./desired.ts"
import type { LockedArtifact } from "./model.ts"
import { buildNormalizedJar } from "./sources/git.ts"

function pin(artifact: LockedArtifact): { algorithm: HashAlgorithm; value: string } {
  const pins = (["sha256", "sha512", "sha1"] as const).flatMap((algorithm) => {
    const value = artifact[algorithm]
    return value ? [{ algorithm, value }] : []
  })
  if (pins.length !== 1) {
    throw new Error(`lock needs exactly one hash, found ${pins.length}`)
  }
  return pins[0]
}

function repoRoot(from = process.cwd()): string {
  let dir = path.resolve(from)
  for (;;) {
    if (existsSync(path.join(dir, ".git")) || existsSync(path.join(dir, "tools", "plugin-lock"))) {
      return dir
    }
    const parent = path.dirname(dir)
    if (parent === dir) return path.resolve(from)
    dir = parent
  }
}

/**
 * Durable jar cache: defaults to `<repo>/.plugin-lock-cache`.
 * Set PLUGIN_LOCK_CACHE to an absolute/relative path to override, or to empty to disable.
 */
function cacheRoot(): string | undefined {
  if (process.env.PLUGIN_LOCK_CACHE !== undefined) {
    const value = process.env.PLUGIN_LOCK_CACHE.trim()
    return value ? path.resolve(value) : undefined
  }
  return path.join(repoRoot(), ".plugin-lock-cache")
}

function cachePath(algorithm: HashAlgorithm, value: string, filename: string): string {
  const safe = basename(filename).replace(/[^A-Za-z0-9._-]+/g, "_")
  return path.join(cacheRoot()!, algorithm, value, safe)
}

async function readCache(algorithm: HashAlgorithm, value: string, filename: string): Promise<Buffer | undefined> {
  const root = cacheRoot()
  if (!root) return undefined
  try {
    const bytes = await readFile(cachePath(algorithm, value, filename))
    if (digest(algorithm, bytes) !== value) return undefined
    return bytes
  } catch {
    return undefined
  }
}

async function writeCache(algorithm: HashAlgorithm, value: string, filename: string, bytes: Buffer): Promise<void> {
  const root = cacheRoot()
  if (!root) return
  const file = cachePath(algorithm, value, filename)
  await mkdir(path.dirname(file), { recursive: true })
  await writeFile(file, bytes)
}

export async function downloadArtifact(artifact: LockedArtifact, outDir: string): Promise<void> {
  const filename = basename(artifact.filename)
  const expected = pin(artifact)
  let fromCache = false
  let bytes = await readCache(expected.algorithm, expected.value, filename)
  if (bytes) {
    fromCache = true
  } else {
    bytes = artifact.source === "git" ? await gitBytes(artifact) : await downloadBytes(artifact.url)
  }
  const hash = digest(expected.algorithm, bytes)
  if (hash !== expected.value) {
    throw new Error(`${expected.algorithm} ${hash} does not match the lock ${expected.value}`)
  }
  if (!fromCache) await writeCache(expected.algorithm, expected.value, filename, bytes)
  await mkdir(outDir, { recursive: true })
  await writeFile(path.join(outDir, filename), bytes)
  console.log(`${filename} ${artifact.version} ${hash}${fromCache ? " (cache)" : ""}`)
}

async function gitBytes(artifact: LockedArtifact): Promise<Buffer> {
  const built = await buildNormalizedJar({
    name: artifact.name,
    source: "git",
    id: artifact.url,
    commit: artifact.version,
    builder: artifact.builder,
    java: artifact.java,
    release: artifact.release,
    task: artifact.task,
    jar: artifact.jar,
    asset: artifact.filename,
  })
  return built.bytes
}
