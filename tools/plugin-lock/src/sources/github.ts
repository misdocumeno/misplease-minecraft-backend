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

/** Stable lock locator: repo + tag. Asset numeric ids in /releases/assets/N are not stable. */
export function githubTagLocator(repo: string, tag: string): string {
  return `https://api.github.com/repos/${repo}/releases/tags/${encodeURIComponent(tag)}`
}

/**
 * Recover owner/repo from a lock URL. Accepts tag locators, asset API URLs, and browser download URLs.
 */
export function githubRepoFromLockUrl(url: string): string | undefined {
  try {
    const parsed = new URL(url)
    if (parsed.hostname === "api.github.com") {
      const match = parsed.pathname.match(/^\/repos\/([^/]+\/[^/]+)\/releases\//)
      return match?.[1]
    }
    if (parsed.hostname === "github.com") {
      const match = parsed.pathname.match(/^\/([^/]+\/[^/]+)\/releases\//)
      return match?.[1]
    }
  } catch {
    return undefined
  }
  return undefined
}

export async function resolveGithubAssetDownloadUrl(
  repo: string,
  tag: string,
  filename: string,
): Promise<string> {
  let found = await release(repo, tag)
  if (!found && !tag.startsWith("v")) {
    found = await release(repo, `v${tag}`)
  }
  if (!found) throw new Error(`github release ${tag} was not found in ${repo}`)
  const jars = jarAssets(found.assets)
  const asset = jars.find((item) => item.name === filename)
  if (!asset) {
    const names = jars.map((item) => item.name).join(", ") || "none"
    throw new Error(`github release ${found.tag_name} has no asset ${filename}; jars: ${names}`)
  }
  // Private releases 404 on browser_download_url; the assets API URL works with a token.
  return asset.url || asset.browser_download_url
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
  // Pin the stable tag locator, not /releases/assets/<id> (ids change when assets are re-uploaded).
  return {
    name: plugin.name,
    source: "github",
    version: found.tag_name,
    filename: asset.name,
    url: githubTagLocator(plugin.id, found.tag_name),
    sha256: digest,
  }
}
