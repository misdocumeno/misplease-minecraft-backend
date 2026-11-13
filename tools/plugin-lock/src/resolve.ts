import { digest, downloadBytes, type HashAlgorithm } from "./http.ts"
import type { DesiredPlugin, LockedArtifact, ResolvedArtifact } from "./model.ts"
import { resolveGithub, resolveGithubAssetDownloadUrl } from "./sources/github.ts"
import { resolveHangar } from "./sources/hangar.ts"
import { resolveModrinth } from "./sources/modrinth.ts"
import { resolvePapermc } from "./sources/papermc.ts"
import { resolvePurpur } from "./sources/purpur.ts"
import { resolveGit } from "./sources/git.ts"
import { resolveJenkins } from "./sources/jenkins.ts"
import { resolveSpiget } from "./sources/spiget.ts"
import { resolveGeysermc } from "./sources/geysermc.ts"

async function resolve(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  switch (plugin.source) {
    case "modrinth":
      return resolveModrinth(plugin)
    case "github":
      return resolveGithub(plugin)
    case "hangar":
      return resolveHangar(plugin)
    case "spiget":
      return resolveSpiget(plugin)
    case "papermc":
      return resolvePapermc(plugin)
    case "purpur":
      return resolvePurpur(plugin)
    case "git":
      return resolveGit(plugin)
    case "jenkins":
      return resolveJenkins(plugin)
    case "geysermc":
      return resolveGeysermc(plugin)
    default:
      throw new Error(`unknown source ${plugin.source || "(empty)"}`)
  }
}

function apiHash(resolved: ResolvedArtifact): { algorithm: HashAlgorithm; value: string } | undefined {
  if (resolved.sha256) return { algorithm: "sha256", value: resolved.sha256 }
  if (resolved.sha512) return { algorithm: "sha512", value: resolved.sha512 }
  if (resolved.sha1) return { algorithm: "sha1", value: resolved.sha1 }
  return undefined
}

export async function lockArtifact(plugin: DesiredPlugin): Promise<LockedArtifact> {
  const resolved = await resolve(plugin)
  // Git pins the commit only — jars are not bit-identical across OS, so no content hash at update.
  if (plugin.source === "git") {
    return {
      name: resolved.name,
      source: resolved.source,
      version: resolved.version,
      filename: resolved.filename,
      url: resolved.url,
      jar: resolved.jar,
      builder: resolved.builder,
      java: resolved.java,
      release: resolved.release,
      task: resolved.task,
    }
  }
  const fromApi = apiHash(resolved)
  let pin = fromApi
  if (!pin) {
    // GitHub lock urls are stable tag locators, not asset download urls — resolve first.
    const downloadUrl =
      plugin.source === "github" && plugin.id
        ? await resolveGithubAssetDownloadUrl(plugin.id, resolved.version, resolved.filename)
        : resolved.url
    pin = { algorithm: "sha256", value: digest("sha256", await downloadBytes(downloadUrl)) }
  }
  console.log(`${resolved.name} ${resolved.version} ${pin.algorithm} ${fromApi ? "from the api" : "from the downloaded jar"}`)
  return {
    name: resolved.name,
    source: resolved.source,
    version: resolved.version,
    filename: resolved.filename,
    url: resolved.url,
    [pin.algorithm]: pin.value,
    minecraft: resolved.minecraft && resolved.minecraft.length > 0 ? resolved.minecraft : undefined,
    task: resolved.task,
    jar: resolved.jar,
    builder: resolved.builder,
    java: resolved.java,
    release: resolved.release,
  }
}

export function compatibilityLine(target: string | undefined, artifact: LockedArtifact): string {
  if (!target) return `${artifact.name} ${artifact.version}`
  if (!artifact.minecraft || artifact.minecraft.length === 0) {
    return `${artifact.name} ${artifact.version} does not declare a Minecraft version`
  }
  if (artifact.minecraft.includes(target)) {
    return `${artifact.name} ${artifact.version} declares ${target}`
  }
  return `${artifact.name} ${artifact.version} does not declare ${target}`
}
