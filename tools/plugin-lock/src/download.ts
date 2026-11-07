import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"
import { restoreActionsCache, saveActionsCache } from "./actions-cache.ts"
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
 * Local jar cache: defaults to `<repo>/.plugin-lock-cache`.
 * Flat layout: `<entryHash>.jar` (sha256 of the lock entry object).
 * On GitHub Actions, each file is also backed by `@actions/cache` key `plugin-jar-<entryHash>`.
 * Set PLUGIN_LOCK_CACHE to override the directory, or empty to disable disk+Actions caching.
 */
function cacheRoot(): string | undefined {
  if (process.env.PLUGIN_LOCK_CACHE !== undefined) {
    const value = process.env.PLUGIN_LOCK_CACHE.trim()
    return value ? path.resolve(value) : undefined
  }
  return path.join(repoRoot(), ".plugin-lock-cache")
}

/** Stable identity of a locked plugin/server row — not the jar content hash. */
export function lockEntryHash(artifact: LockedArtifact): string {
  const keys = Object.keys(artifact).sort() as (keyof LockedArtifact)[]
  const canonical: Record<string, unknown> = {}
  for (const key of keys) {
    const value = artifact[key]
    if (value !== undefined) canonical[key] = value
  }
  return createHash("sha256").update(JSON.stringify(canonical)).digest("hex")
}

function cacheFile(entryHash: string): string {
  return path.join(cacheRoot()!, `${entryHash}.jar`)
}

async function readDisk(entryHash: string): Promise<Buffer | undefined> {
  const root = cacheRoot()
  if (!root) return undefined
  try {
    return await readFile(cacheFile(entryHash))
  } catch {
    return undefined
  }
}

async function writeDisk(entryHash: string, bytes: Buffer): Promise<void> {
  const root = cacheRoot()
  if (!root) return
  await mkdir(root, { recursive: true })
  await writeFile(cacheFile(entryHash), bytes)
}

export async function downloadArtifact(artifact: LockedArtifact, outDir: string): Promise<void> {
  const filename = basename(artifact.filename)
  const expected = pin(artifact)
  const entryHash = lockEntryHash(artifact)
  const root = cacheRoot()
  const absolute = root ? cacheFile(entryHash) : undefined

  let source: "disk" | "actions" | "network" = "network"
  let bytes = await readDisk(entryHash)
  if (bytes) {
    source = "disk"
  } else if (absolute && (await restoreActionsCache(absolute, entryHash))) {
    bytes = await readDisk(entryHash)
    if (bytes) source = "actions"
  }

  if (!bytes) {
    bytes = artifact.source === "git" ? await gitBytes(artifact) : await downloadBytes(artifact.url)
    source = "network"
  }

  const hash = digest(expected.algorithm, bytes)
  if (hash !== expected.value) {
    // Git jars are rebuilt from the locked commit; bytecode is not bit-identical across OS/JDK.
    // The commit is the pin — keep the rebuilt bytes and do not fail the download.
    if (artifact.source === "git") {
      console.log(
        `${filename} git rebuild ${expected.algorithm} ${hash.slice(0, 12)}… (lock had ${expected.value.slice(0, 12)}…)`,
      )
    } else {
      throw new Error(`${expected.algorithm} ${hash} does not match the lock ${expected.value}`)
    }
  }

  if (source === "network" && absolute) {
    await writeDisk(entryHash, bytes)
    await saveActionsCache(absolute, entryHash)
  }

  await mkdir(outDir, { recursive: true })
  await writeFile(path.join(outDir, filename), bytes)
  const tag = source === "network" ? "" : source === "actions" ? " (actions-cache)" : " (cache)"
  console.log(`${filename} ${artifact.version} ${entryHash.slice(0, 12)}${tag}`)
}

async function gitBytes(artifact: LockedArtifact): Promise<Buffer> {
  const legacyPath = artifact.jarPath ?? artifact.jar
  const built = await buildNormalizedJar({
    name: artifact.name,
    source: "git",
    id: artifact.url,
    commit: artifact.version,
    builder: artifact.builder,
    java: artifact.java,
    release: artifact.release,
    task: artifact.task,
    jarPath: legacyPath?.includes("/") ? legacyPath : undefined,
    jar: legacyPath && !legacyPath.includes("/") ? legacyPath : undefined,
  })
  return built.bytes
}
