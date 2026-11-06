/** Compare Minecraft / Paper game versions (1.20.1, 26.3, …). */
export function compareMinecraft(a: string, b: string): number {
  const pa = parts(a)
  const pb = parts(b)
  const n = Math.max(pa.length, pb.length)
  for (let i = 0; i < n; i += 1) {
    const da = pa[i] ?? 0
    const db = pb[i] ?? 0
    if (da !== db) return da < db ? -1 : 1
  }
  return 0
}

function parts(version: string): number[] {
  return version.split(".").map((chunk) => {
    const match = chunk.match(/^(\d+)/)
    return match ? Number(match[1]) : 0
  })
}

/** True if any declared game version is at most the server ceiling. */
export function fitsMinecraftCeiling(gameVersions: string[] | undefined, ceiling: string): boolean {
  if (!gameVersions || gameVersions.length === 0) return false
  return gameVersions.some((version) => compareMinecraft(version, ceiling) <= 0)
}

/** Newest-first list → first entry that fits the ceiling, or undefined. */
export function pickNewestFittingCeiling<T>(
  newestFirst: T[],
  gameVersionsOf: (item: T) => string[] | undefined,
  ceiling: string,
): T | undefined {
  return newestFirst.find((item) => fitsMinecraftCeiling(gameVersionsOf(item), ceiling))
}
