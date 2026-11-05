import { getJson } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface PaperDownload {
  name: string
  checksums: { sha256: string }
  url: string
}

interface PaperBuild {
  id: number
  channel: string
  downloads: { "server:default": PaperDownload }
}

function paperChannel(channel: string | undefined): string {
  const name = (channel ?? "release").toLowerCase()
  if (name === "release") return "STABLE"
  if (name === "beta") return "BETA"
  if (name === "alpha") return "ALPHA"
  throw new Error(`unknown channel ${channel}`)
}

export async function resolvePapermc(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const project = plugin.id || "paper"
  if (!plugin.version) throw new Error("papermc needs a version")
  const channel = paperChannel(plugin.channel)
  const builds = await getJson<PaperBuild[]>(
    `https://fill.papermc.io/v3/projects/${encodeURIComponent(project)}/versions/${encodeURIComponent(plugin.version)}/builds?channel=${channel}`,
  )
  const sorted = [...builds].sort((a, b) => b.id - a.id)
  const chosen = plugin.build ? sorted.find((build) => String(build.id) === plugin.build) : sorted[0]
  if (!chosen) throw new Error(`papermc has no ${channel} build ${plugin.build ?? ""}`.trim())
  const file = chosen.downloads["server:default"]
  return {
    name: plugin.name,
    source: "papermc",
    version: `${plugin.version}+${chosen.id}`,
    filename: file.name,
    url: file.url,
    sha256: file.checksums.sha256,
    minecraft: project === "paper" || project === "folia" ? [plugin.version] : undefined,
  }
}
