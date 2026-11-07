import { readFile } from "node:fs/promises"
import { parse } from "yaml"
import type { DesiredFile, DesiredPlugin } from "./model.ts"

interface RawEntry {
  name?: string
  source?: string
  repo?: string
  project?: string
  slug?: string
  id?: string | number
  resourceId?: number
  version?: string | number
  build?: string | number
  asset?: string
  channel?: string
  channe?: string
  commit?: string
  builder?: string
  task?: string
  jar?: string
  jarPath?: string
  job?: string
  server?: string
  java?: string | number
}

function asString(value: string | number | undefined): string | undefined {
  if (value === undefined) return undefined
  return String(value)
}

function repoLabel(repo: string): string {
  const cleaned = repo.replace(/\.git$/i, "").replace(/\/+$/, "")
  const parts = cleaned.split("/").filter(Boolean)
  return parts[parts.length - 1] ?? repo
}

function entryName(raw: RawEntry): string {
  if (raw.name) return raw.name
  if (raw.slug) return raw.slug
  if (raw.project) return raw.project.split("/")[1] ?? raw.project
  if (raw.repo) return repoLabel(raw.repo)
  if (raw.job) return raw.job
  if (raw.resourceId !== undefined) return `spiget-${raw.resourceId}`
  if (raw.id !== undefined) return String(raw.id)
  return "unnamed"
}

function toPlugin(raw: RawEntry): DesiredPlugin {
  const id = raw.id ?? raw.repo ?? raw.project ?? raw.slug ?? raw.resourceId ?? raw.job
  return {
    name: entryName(raw),
    source: String(raw.source ?? ""),
    id: asString(id),
    version: asString(raw.version),
    build: asString(raw.build),
    // `jar` = filename or `/regex/`. `jarPath` = git repo-relative path. `asset` → `jar` legacy alias.
    asset: raw.asset,
    channel: raw.channel ?? raw.channe,
    commit: raw.commit,
    builder: raw.builder,
    task: raw.task,
    jar: raw.jar ?? raw.asset,
    jarPath: asString(raw.jarPath),
    server: raw.server,
    java: asString(raw.java),
  }
}

export async function readDesired(path: string): Promise<DesiredFile> {
  const text = await readFile(path, "utf8")
  const raw = parse(text) as {
    minecraft?: string
    platform?: string
    server?: RawEntry
    plugins?: RawEntry[]
  }
  if (!raw || !Array.isArray(raw.plugins)) {
    throw new Error(`${path} must contain a plugins list`)
  }
  return {
    minecraft: raw.minecraft,
    server: raw.server ? toPlugin(raw.server) : undefined,
    plugins: raw.plugins.map(toPlugin),
  }
}

export function basename(filename: string): string {
  const name = filename.split(/[\\/]/).pop() ?? filename
  if (name !== filename || name.length === 0 || name === "." || name === "..") {
    throw new Error(`unsafe filename: ${filename}`)
  }
  return name
}
