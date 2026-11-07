import { getJson } from "../http.ts"
import { matchJar } from "../jar-match.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface GitHubAsset {
  name: string
  url: string
  browser_download_url: string
  digest?: string | null
}

interface GitHubRelease {
  tag_name: string
  assets: GitHubAsset[]
}

function jarAssets(assets: GitHubAsset[]): GitHubAsset[] {
  return assets.filter((asset) => {
    const name = asset.name.toLowerCase()
    return name.endsWith(".jar") && !name.includes("sources") && !name.includes("javadoc")
  })
}

async function release(repo: string, tag: string): Promise<GitHubRelease | undefined> {
  const url = `https://api.github.com/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`
  try {
    return await getJson<GitHubRelease>(url, { accept: "application/vnd.github+json" })
  } catch (error) {
    if (error instanceof Error && error.message.startsWith("404 ")) return undefined
    throw error
  }
}

export async function resolveGithub(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  if (!plugin.id || !plugin.id.includes("/")) throw new Error("github plugin needs owner/repo")
  const headers = { accept: "application/vnd.github+json" }
  let found: GitHubRelease | undefined
  if (plugin.version) {
    found = await release(plugin.id, plugin.version)
    if (!found && !plugin.version.startsWith("v")) {
      found = await release(plugin.id, `v${plugin.version}`)
    }
    if (!found) throw new Error(`github release ${plugin.version} was not found`)
  } else {
    found = await getJson<GitHubRelease>(
      `https://api.github.com/repos/${plugin.id}/releases/latest`,
      headers,
    )
  }

  const jars = jarAssets(found.assets)
  // `jar:` exact name or `/regex/` (same as Jenkins). Legacy `asset:` is folded into `jar` in desired.ts.
  const filename = matchJar(
    jars.map((item) => item.name),
    plugin.jar,
    `github release ${found.tag_name}`,
  )
  const asset = jars.find((item) => item.name === filename)!

  const digest = asset.digest?.startsWith("sha256:") ? asset.digest.slice("sha256:".length) : undefined
  // Private releases 404 on browser_download_url; the assets API URL works with a token.
  return {
    name: plugin.name,
    source: "github",
    version: found.tag_name,
    filename: asset.name,
    url: asset.url || asset.browser_download_url,
    sha256: digest,
  }
}
