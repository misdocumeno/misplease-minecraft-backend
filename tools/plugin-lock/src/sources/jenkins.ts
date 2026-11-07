import { digest, downloadBytes } from "../http.ts"
import { matchJar } from "../jar-match.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface JenkinsArtifact {
  fileName: string
  relativePath: string
}

interface JenkinsBuild {
  number?: number
  artifacts?: JenkinsArtifact[]
}

function buildUrl(plugin: DesiredPlugin): string {
  const server = plugin.server?.replace(/\/$/, "")
  if (!server?.startsWith("https://")) {
    throw new Error(`${plugin.name} needs an https Jenkins server`)
  }
  const job = plugin.id ?? ""
  if (!job || job.split("/").some((part) => part.length === 0 || part === "." || part === "..")) {
    throw new Error(`${plugin.name} needs a Jenkins job`)
  }
  // Numeric build, or lastSuccessfulBuild when omitted / explicitly requested.
  const build =
    plugin.build && /^[0-9]+$/.test(plugin.build)
      ? plugin.build
      : plugin.build === "lastSuccessfulBuild" || !plugin.build
        ? "lastSuccessfulBuild"
        : (() => {
            throw new Error(`${plugin.name} needs a Jenkins build number or lastSuccessfulBuild`)
          })()
  const segments = job.split("/").map((part) => encodeURIComponent(part)).join("/job/")
  return `${server}/job/${segments}/${build}`
}

export async function resolveJenkins(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const base = buildUrl(plugin)
  const response = await fetch(`${base}/api/json?tree=number,artifacts[fileName,relativePath]`, {
    headers: { "user-agent": "misplease-plugin-lock", accept: "application/json" },
    signal: AbortSignal.timeout(60_000),
  })
  if (!response.ok) throw new Error(`${response.status} for ${base}`)
  const build = (await response.json()) as JenkinsBuild
  const jars = (build.artifacts ?? []).filter((artifact) => artifact.fileName.toLowerCase().endsWith(".jar"))
  // `jar:` exact name or `/regex/` (same as GitHub). Legacy `asset:` is folded into `jar` in desired.ts.
  const filename = matchJar(
    jars.map((artifact) => artifact.fileName),
    plugin.jar,
    plugin.name,
  )
  const artifact = jars.find((row) => row.fileName === filename)!
  const artifactPath = artifact.relativePath.split("/").map((part) => encodeURIComponent(part)).join("/")
  // Resolve lastSuccessfulBuild to a concrete /job/.../N/ URL so the lock is reproducible.
  const number = build.number
  const concreteBase =
    typeof number === "number" && Number.isFinite(number)
      ? base.replace(/\/lastSuccessfulBuild$/, `/${number}`)
      : base
  const url = `${concreteBase}/artifact/${artifactPath}`
  const bytes = await downloadBytes(url)
  return {
    name: plugin.name,
    source: "jenkins",
    version: typeof number === "number" ? String(number) : (plugin.build ?? ""),
    filename: artifact.fileName,
    url,
    sha256: digest("sha256", bytes),
  }
}
