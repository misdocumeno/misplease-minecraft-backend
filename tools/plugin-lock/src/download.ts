import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"
import { restoreActionsCache, saveActionsCache } from "./actions-cache.ts"
import { digest, downloadBytes, type HashAlgorithm } from "./http.ts"
import { basename } from "./desired.ts"
import type { LockedArtifact } from "./model.ts"
import { buildNormalizedJar } from "./sources/git.ts"
import { githubRepoFromLockUrl, resolveGithubAssetDownloadUrl } from "./sources/github.ts"

function pin(artifact: LockedArtifact): { algorithm: HashAlgorithm; value: string } | undefined {
  const pins = (["sha256", "sha512", "sha1"] as const).flatMap((algorithm) => {
    const value = artifact[algorithm]
    return value ? [{ algorithm, value }] : []
  })
  if (pins.length === 0) return undefined
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
 * Flat layout: `<cacheKey>.jar` — prefer content hash so ephemeral lock URL churn does not miss.
 * On GitHub Actions, each file is also backed by `@actions/cache` key `plugin-jar-<cacheKey>`.
 * Set PLUGIN_LOCK_CACHE to override the directory, or empty to disable disk+Actions caching.
 */
function cacheRoot(): string | undefined {
  if (process.env.PLUGIN_LOCK_CACHE !== undefined) {
    const value = process.env.PLUGIN_LOCK_CACHE.trim()
    return value ? path.resolve(value) : undefined
  }
  return path.join(repoRoot(), ".plugin-lock-cache")
}

/** Fallback identity when no content hash is pinned (e.g. source:git before build). */
export function lockEntryHash(artifact: LockedArtifact): string {
  const keys = Object.keys(artifact).sort() as (keyof LockedArtifact)[]
  const canonical: Record<string, unknown> = {}
  for (const key of keys) {
    const value = artifact[key]
    // GitHub asset API ids are not stable; never let url dominate the identity.
    if (key === "url" && artifact.source === "github") continue
    if (value !== undefined) canonical[key] = value
  }
  return createHash("sha256").update(JSON.stringify(canonical)).digest("hex")
}

/** Cache identity: content pin when present, else lock entry (sans ephemeral github url). */
export function artifactCacheKey(artifact: LockedArtifact): string {
  const expected = pin(artifact)
  if (expected) return `${expected.algorithm}-${expected.value}`
  return lockEntryHash(artifact)
}

function cacheFile(cacheKey: string): string {
  return path.join(cacheRoot()!, `${cacheKey}.jar`)
}

async function readDisk(cacheKey: string): Promise<Buffer | undefined> {
  const root = cacheRoot()
  if (!root) return undefined
  try {
    return await readFile(cacheFile(cacheKey))
  } catch {
    return undefined
  }
}

async function writeDisk(cacheKey: string, bytes: Buffer): Promise<void> {
  const root = cacheRoot()
  if (!root) return
  await mkdir(root, { recursive: true })
  await writeFile(cacheFile(cacheKey), bytes)
}

async function networkBytes(artifact: LockedArtifact): Promise<Buffer> {
  if (artifact.source === "git") return gitBytes(artifact)
  if (artifact.source === "github") {
    const repo = githubRepoFromLockUrl(artifact.url)
    if (!repo) {
      throw new Error(`${artifact.name}: cannot parse owner/repo from lock url ${artifact.url}`)
    }
    // Always re-resolve asset id from repo + tag + filename (asset numeric ids are not stable).
    const url = await resolveGithubAssetDownloadUrl(repo, artifact.version, basename(artifact.filename))
    return downloadBytes(url)
  }
  return downloadBytes(artifact.url)
}

export async function downloadArtifact(artifact: LockedArtifact, outDir: string): Promise<void> {
  const filename = basename(artifact.filename)
  const expected = pin(artifact)
  if (!expected && artifact.source !== "git") {
    throw new Error(`${artifact.name}: lock needs a content hash`)
  }
  const cacheKey = artifactCacheKey(artifact)
  const root = cacheRoot()
  const absolute = root ? cacheFile(cacheKey) : undefined

  let source: "disk" | "actions" | "network" = "network"
  let bytes = await readDisk(cacheKey)
  if (bytes) {
    source = "disk"
  } else if (absolute && (await restoreActionsCache(absolute, cacheKey))) {
    bytes = await readDisk(cacheKey)
    if (bytes) source = "actions"
  }

  if (!bytes) {
    bytes = await networkBytes(artifact)
    source = "network"
  }

  if (expected) {
    const hash = digest(expected.algorithm, bytes)
    if (hash !== expected.value) {
      throw new Error(`${expected.algorithm} ${hash} does not match the lock ${expected.value}`)
    }
  }

  if (source === "network" && absolute) {
    await writeDisk(cacheKey, bytes)
    await saveActionsCache(absolute, cacheKey)
  }

  await mkdir(outDir, { recursive: true })
  await writeFile(path.join(outDir, filename), bytes)
  const tag = source === "network" ? "" : source === "actions" ? " (actions-cache)" : " (cache)"
  console.log(`${filename} ${artifact.version} ${cacheKey.slice(0, 12)}${tag}`)
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
  })
  return built.bytes
}
