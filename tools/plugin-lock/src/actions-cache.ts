import path from "node:path"

/** True when the Actions cache service env is present (GHA runner). */
export function actionsCacheEnabled(): boolean {
  return Boolean(process.env.ACTIONS_CACHE_URL && process.env.ACTIONS_RUNTIME_TOKEN)
}

export function actionsCacheKey(entryHash: string): string {
  return `plugin-jar-${entryHash}`
}

/**
 * Path relative to GITHUB_WORKSPACE when possible — required by @actions/cache.
 * Falls back to absolute path outside Actions.
 */
export function actionsCachePath(absoluteFile: string): string {
  const workspace = process.env.GITHUB_WORKSPACE
  if (!workspace) return absoluteFile
  const relative = path.relative(workspace, absoluteFile)
  if (relative.startsWith("..") || path.isAbsolute(relative)) return absoluteFile
  return relative
}

export async function restoreActionsCache(absoluteFile: string, entryHash: string): Promise<boolean> {
  if (!actionsCacheEnabled()) return false
  const cache = await import("@actions/cache")
  const key = actionsCacheKey(entryHash)
  const paths = [actionsCachePath(absoluteFile)]
  try {
    const hit = await cache.restoreCache(paths, key)
    return Boolean(hit)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    console.warn(`actions-cache restore ${key.slice(0, 24)}…: ${message}`)
    return false
  }
}

export async function saveActionsCache(absoluteFile: string, entryHash: string): Promise<void> {
  if (!actionsCacheEnabled()) return
  const cache = await import("@actions/cache")
  const key = actionsCacheKey(entryHash)
  const paths = [actionsCachePath(absoluteFile)]
  try {
    await cache.saveCache(paths, key)
  } catch (error) {
    // Parallel matrix jobs often race the same plugin-jar-* key; treat as OK.
    const message = error instanceof Error ? error.message : String(error)
    if (/already exists|ReserveCacheError|another (workflow|GitHub)/i.test(message)) return
    console.warn(`actions-cache save ${key.slice(0, 24)}…: ${message}`)
  }
}
