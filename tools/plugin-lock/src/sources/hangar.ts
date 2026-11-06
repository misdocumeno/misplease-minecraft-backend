import { getJson } from "../http.ts"
import { pickNewestFittingCeiling } from "../minecraft-version.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface HangarVersion {
  name: string
  channel?: { name?: string }
  downloads: Record<
    string,
    {
      downloadUrl?: string
      externalUrl?: string | null
      fileInfo?: { name?: string; sha256Hash?: string }
    }
  >
  platformDependencies?: Record<string, string[]>
}

interface HangarPage {
  result: HangarVersion[]
}

function channelName(channel: string | undefined): string {
  const name = (channel ?? "release").toLowerCase()
  if (name === "release") return "Release"
  if (name === "beta") return "Beta"
  if (name === "alpha") return "Alpha"
  throw new Error(`unknown channel ${channel}`)
}

function projectPath(id: string): string {
  const [author, slug] = id.split("/")
  if (!author || !slug || id.split("/").length !== 2) {
    throw new Error("hangar plugin needs author/slug")
  }
  return `${encodeURIComponent(author)}/${encodeURIComponent(slug)}`
}

function paperGameVersions(version: HangarVersion): string[] | undefined {
  return version.platformDependencies?.PAPER ?? version.platformDependencies?.PURPUR
}

function toResolved(plugin: DesiredPlugin, version: HangarVersion): ResolvedArtifact {
  const platform = version.downloads.PAPER ? "PAPER" : version.downloads.PURPUR ? "PURPUR" : undefined
  if (!platform) throw new Error(`hangar version ${version.name} has no Paper or Purpur jar`)
  const download = version.downloads[platform]
  if (!download.downloadUrl || download.externalUrl) {
    throw new Error(`hangar version ${version.name} is an external link`)
  }
  const filename = download.fileInfo?.name
  if (!filename) throw new Error(`hangar version ${version.name} has no filename`)
  if (plugin.asset && filename !== plugin.asset) {
    throw new Error(`hangar version ${version.name} file is ${filename}, not ${plugin.asset}`)
  }
  return {
    name: plugin.name,
    source: "hangar",
    version: version.name,
    filename,
    url: download.downloadUrl,
    sha256: download.fileInfo?.sha256Hash,
    minecraft: version.platformDependencies?.[platform],
  }
}

async function listChannelVersions(plugin: DesiredPlugin): Promise<HangarVersion[]> {
  if (!plugin.id) throw new Error("hangar plugin needs an id")
  const project = projectPath(plugin.id)
  const wanted = channelName(plugin.channel)
  const matches: HangarVersion[] = []
  for (let offset = 0; offset < 500; offset += 25) {
    const page = await getJson<HangarPage>(
      `https://hangar.papermc.io/api/v1/projects/${project}/versions?limit=25&offset=${offset}`,
    )
    matches.push(...page.result.filter((version) => version.channel?.name === wanted))
    if (page.result.length < 25) break
  }
  return matches
}

export async function resolveHangar(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  if (!plugin.id) throw new Error("hangar plugin needs an id")
  const project = projectPath(plugin.id)
  if (plugin.version) {
    const version = await getJson<HangarVersion>(
      `https://hangar.papermc.io/api/v1/projects/${project}/versions/${encodeURIComponent(plugin.version)}`,
    )
    return toResolved(plugin, version)
  }

  const matches = await listChannelVersions(plugin)
  if (plugin.minecraftCeiling) {
    const chosen = pickNewestFittingCeiling(matches, paperGameVersions, plugin.minecraftCeiling)
    if (!chosen) {
      throw new Error(
        `hangar has no ${channelName(plugin.channel)} Paper/Purpur jar with a game version ≤ ${plugin.minecraftCeiling}`,
      )
    }
    return toResolved(plugin, chosen)
  }

  const match = matches[0]
  if (!match) throw new Error(`hangar has no ${channelName(plugin.channel)} version`)
  return toResolved(plugin, match)
}
