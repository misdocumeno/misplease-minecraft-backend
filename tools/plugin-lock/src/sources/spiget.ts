import { getJson } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface SpigetVersion {
  id: number
  name: string
}

interface SpigetResource {
  external?: boolean
  version?: { id?: number }
}

function filename(pluginName: string, versionName: string): string {
  const safe = `${pluginName}-${versionName}`.replace(/[^A-Za-z0-9._-]+/g, "_")
  return safe.toLowerCase().endsWith(".jar") ? safe : `${safe}.jar`
}

async function findVersion(resourceId: string, wanted: string): Promise<SpigetVersion> {
  if (/^\d+$/.test(wanted)) {
    return getJson<SpigetVersion>(`https://api.spiget.org/v2/resources/${resourceId}/versions/${wanted}`)
  }
  for (let page = 0; page < 40; page += 1) {
    const batch = await getJson<SpigetVersion[]>(
      `https://api.spiget.org/v2/resources/${resourceId}/versions?size=50&page=${page}&sort=-releaseDate`,
    )
    if (batch.length === 0) break
    const match = batch.find((version) => version.name === wanted)
    if (match) return match
  }
  throw new Error(`spiget resource ${resourceId} has no version ${wanted}`)
}

export async function resolveSpiget(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  if (!plugin.id || !/^\d+$/.test(plugin.id)) throw new Error("spiget plugin needs a numeric id")
  if (plugin.minecraftCeiling) {
    throw new Error(
      "spiget has no per-jar Minecraft versions (only resource-level testedVersions); cannot apply --minecraft",
    )
  }
  const resource = await getJson<SpigetResource>(`https://api.spiget.org/v2/resources/${plugin.id}`)
  if (resource.external) {
    throw new Error("spiget resource is an external link and has no direct jar")
  }
  const version = plugin.version
    ? await findVersion(plugin.id, plugin.version)
    : await getJson<SpigetVersion>(`https://api.spiget.org/v2/resources/${plugin.id}/versions/latest`)
  if (plugin.version && /^\d+$/.test(plugin.version) && String(version.id) !== plugin.version) {
    throw new Error(`spiget returned version ${version.id} instead of ${plugin.version}`)
  }

  const current = resource.version?.id !== undefined && String(version.id) === String(resource.version.id)
  // api.spiget.org/proxy is Cloudflare-rate-limited; avocado serves old version jars.
  const proxy = `https://avocado.api.spiget.org/v2/resources/${plugin.id}/versions/${version.id}/download/proxy`
  const url = current ? `https://cdn.spiget.org/file/spiget-resources/${plugin.id}.jar` : proxy
  return {
    name: plugin.name,
    source: "spiget",
    version: String(version.id),
    filename: filename(plugin.name, version.name),
    url,
  }
}
