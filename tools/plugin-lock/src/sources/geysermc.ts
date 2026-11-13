import { getJson } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

/**
 * Official GeyserMC download API (Velocity/Spigot Floodgate are not on Modrinth).
 * desired: source: geysermc, id: geyser|floodgate, asset: velocity|spigot|bungee
 * version omitted → latest; else pin that version line (latest build of it).
 */
interface GeyserBuild {
  version: string
  build: number
  downloads: Record<string, { name: string; sha256: string }>
}

export async function resolveGeysermc(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const project = (plugin.id || plugin.name || "").trim().toLowerCase()
  if (!project) throw new Error(`${plugin.name}: geysermc needs id (geyser|floodgate)`)
  const platform = (plugin.asset || "").trim().toLowerCase()
  if (!platform) throw new Error(`${plugin.name}: geysermc needs asset (velocity|spigot|bungee)`)

  const versionPath = plugin.version?.trim() ? encodeURIComponent(plugin.version.trim()) : "latest"
  const meta = await getJson<GeyserBuild>(
    `https://download.geysermc.org/v2/projects/${encodeURIComponent(project)}/versions/${versionPath}/builds/latest`,
  )
  const file = meta.downloads[platform]
  if (!file) {
    throw new Error(
      `${plugin.name}: no download for platform ${platform}; have ${Object.keys(meta.downloads).join(", ")}`,
    )
  }
  const url =
    `https://download.geysermc.org/v2/projects/${encodeURIComponent(project)}` +
    `/versions/${encodeURIComponent(meta.version)}/builds/${meta.build}/downloads/${encodeURIComponent(platform)}`

  return {
    name: plugin.name,
    source: "geysermc",
    version: `${meta.version}-b${meta.build}`,
    filename: file.name,
    url,
    sha256: file.sha256,
  }
}
