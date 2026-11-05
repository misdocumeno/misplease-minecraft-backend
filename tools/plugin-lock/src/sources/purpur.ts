import { getJson } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface PurpurIndex {
  builds: { latest: string; all: string[] }
}

export async function resolvePurpur(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  if (!plugin.version) throw new Error("purpur needs a minecraft version")
  const index = await getJson<PurpurIndex>(`https://api.purpurmc.org/v2/purpur/${encodeURIComponent(plugin.version)}`)
  const build = plugin.build ?? index.builds.latest
  if (!index.builds.all.includes(build)) {
    throw new Error(`purpur ${plugin.version} has no build ${build}`)
  }
  return {
    name: plugin.name,
    source: "purpur",
    version: `${plugin.version}+${build}`,
    filename: `purpur-${plugin.version}-${build}.jar`,
    url: `https://api.purpurmc.org/v2/purpur/${encodeURIComponent(plugin.version)}/${encodeURIComponent(build)}/download`,
    minecraft: [plugin.version],
  }
}
