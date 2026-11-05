import { basename } from "../desired.ts"
import { digest, downloadBytes } from "../http.ts"
import type { DesiredPlugin, ResolvedArtifact } from "../model.ts"

interface JenkinsArtifact {
  fileName: string
  relativePath: string
}

interface JenkinsBuild {
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
  if (!plugin.build || !/^[0-9]+$/.test(plugin.build)) {
    throw new Error(`${plugin.name} needs a Jenkins build number`)
  }
  const segments = job.split("/").map((part) => encodeURIComponent(part)).join("/job/")
  return `${server}/job/${segments}/${plugin.build}`
}

export async function resolveJenkins(plugin: DesiredPlugin): Promise<ResolvedArtifact> {
  const base = buildUrl(plugin)
  const response = await fetch(`${base}/api/json?tree=artifacts[fileName,relativePath]`, {
    headers: { "user-agent": "misplease-plugin-lock", accept: "application/json" },
    signal: AbortSignal.timeout(60_000),
  })
  if (!response.ok) throw new Error(`${response.status} for ${base}`)
  const build = (await response.json()) as JenkinsBuild
  const jars = (build.artifacts ?? []).filter((artifact) => artifact.fileName.toLowerCase().endsWith(".jar"))
  const named = plugin.asset ? jars.filter((artifact) => artifact.fileName === basename(plugin.asset ?? "")) : jars
  if (named.length !== 1) {
    const listed = (named.length > 0 ? named : jars).map((artifact) => artifact.fileName).join(", ")
    throw new Error(`${plugin.name} matched ${named.length} Jenkins jars${listed ? `: ${listed}` : ""}`)
  }
  const artifact = named[0]
  const artifactPath = artifact.relativePath.split("/").map((part) => encodeURIComponent(part)).join("/")
  const url = `${base}/artifact/${artifactPath}`
  const bytes = await downloadBytes(url)
  return {
    name: plugin.name,
    source: "jenkins",
    version: plugin.build ?? "",
    filename: artifact.fileName,
    url,
    sha256: digest("sha256", bytes),
  }
}
