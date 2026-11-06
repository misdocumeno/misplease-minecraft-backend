import path from "node:path"

/**
 * True when the Actions cache service env is present (GHA runner).
 * Modern runners often expose ACTIONS_RESULTS_URL (cache v2) without ACTIONS_CACHE_URL.
 */
export function actionsCacheEnabled(): boolean {
  if (!process.env.ACTIONS_RUNTIME_TOKEN) return false
  return Boolean(process.env.ACTIONS_CACHE_URL || process.env.ACTIONS_RESULTS_URL)
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
  return relative.replaceAll("\\", "/")
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
    console.warn(`actions-cache restore ${key}: ${message}`)
    return false
  }
}

export async function saveActionsCache(absoluteFile: string, entryHash: string): Promise<void> {
  if (!actionsCacheEnabled()) return
  const cache = await import("@actions/cache")
  const key = actionsCacheKey(entryHash)
  const paths = [actionsCachePath(absoluteFile)]
  try {
    const id = await cache.saveCache(paths, key)
    if (id === -1) {
      console.warn(`actions-cache save ${key}: returned -1 (not saved)`)
      return
    }
    console.log(`actions-cache saved ${key.slice(0, 28)}… id=${id}`)
  } catch (error) {
    // Parallel matrix jobs often race the same plugin-jar-* key; treat as OK.
    const message = error instanceof Error ? error.message : String(error)
    if (/already exists|ReserveCacheError|another (workflow|GitHub)/i.test(message)) return
    console.warn(`actions-cache save ${key}: ${message}`)
  }
}
