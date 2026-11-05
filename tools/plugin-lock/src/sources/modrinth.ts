import { getJson } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface ModrinthFile {
  filename: string
  url: string
  primary?: boolean
  hashes?: { sha256?: string; sha512?: string; sha1?: string }
}

interface ModrinthVersion {
  version_number: string
  version_type: string
  game_versions: string[]
  loaders: string[]
  files: ModrinthFile[]
}

const SERVER_LOADERS = new Set(["paper", "purpur", "bukkit", "spigot"])

function acceptedTypes(channel: string | undefined): Set<string> {
  const name = (channel ?? "release").toLowerCase()
  if (name === "release") return new Set(["release"])
  if (name === "beta") return new Set(["beta"])
  if (name === "alpha") return new Set(["alpha"])
  throw new Error(`unknown channel ${channel}`)
}

function pickFile(version: ModrinthVersion, asset: string | undefined): ModrinthFile {
  const jars = version.files.filter((file) => {
    const filename = file.filename.toLowerCase()
    return filename.endsWith(".jar") && !filename.includes("sources") && !filename.includes("javadoc")
  })
  if (asset) {
    const match = jars.find((file) => file.filename === asset)
    if (!match) {
      const names = jars.map((file) => file.filename).join(", ") || "none"
      throw new Error(`modrinth version ${version.version_number} has no file ${asset}; jars: ${names}`)
    }
    return match
  }
  const primary = jars.find((file) => file.primary)
  if (primary) return primary
  if (jars.length === 1) return jars[0]
  const names = jars.map((file) => file.filename).join(", ") || "none"
  throw new Error(`modrinth version ${version.version_number} needs an exact asset name; jars: ${names}`)
}

export async function resolveModrinth(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  if (!plugin.id) throw new Error("modrinth plugin needs an id")
  const types = acceptedTypes(plugin.channel)
  const matches: ModrinthVersion[] = []
  for (let offset = 0; offset < 2000; offset += 100) {
    const query = new URLSearchParams({ limit: "100", offset: String(offset) })
    const page = await getJson<ModrinthVersion[]>(
      `https://api.modrinth.com/v2/project/${encodeURIComponent(plugin.id)}/version?${query}`,
    )
    if (page.length === 0) break
    matches.push(
      ...page.filter(
        (version) =>
          types.has(version.version_type) && version.loaders.some((loader) => SERVER_LOADERS.has(loader)),
      ),
    )
    if (plugin.version ? matches.some((version) => version.version_number === plugin.version) : matches.length > 0) {
      break
    }
    if (page.length < 100) break
  }

  const chosen = plugin.version
    ? matches.find((version) => version.version_number === plugin.version)
    : matches[0]
  if (!chosen) {
    const wanted = plugin.version ? `version ${plugin.version}` : `${plugin.channel ?? "release"} version`
    throw new Error(`modrinth has no ${wanted} for a server loader`)
  }

  const file = pickFile(chosen, plugin.asset)
  return {
    name: plugin.name,
    source: "modrinth",
    version: chosen.version_number,
    filename: file.filename,
    url: file.url,
    sha256: file.hashes?.sha256,
    sha512: file.hashes?.sha512,
    sha1: file.hashes?.sha1,
    minecraft: chosen.game_versions,
  }
}
