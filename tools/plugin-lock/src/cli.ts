import { readFile, writeFile } from "node:fs/promises"
import { parseArgs } from "node:util"
import { parse, stringify } from "yaml"
import { readDesired } from "./desired.ts"
import { downloadArtifact } from "./download.ts"
import type { DesiredPlugin, LockFile, LockedArtifact } from "./model.ts"
import { compatibilityLine, lockArtifact } from "./resolve.ts"

const { positionals, values } = parseArgs({
  args: process.argv.slice(2),
  options: {
    desired: { type: "string" },
    lock: { type: "string" },
    out: { type: "string" },
    "server-out": { type: "string" },
    only: { type: "string", multiple: true },
    minecraft: { type: "string" },
  },
  allowPositionals: true,
})

/** Sources that expose per-jar game versions so --minecraft can cap selection. */
const MINECRAFT_CEILING_SOURCES = new Set(["modrinth", "hangar", "papermc", "purpur"])

function usage(): never {
  console.error(`plugin-lock update --desired desired.yml --lock lock.yml [--only name ...] [--minecraft <serverVer>]
plugin-lock download --lock lock.yml --out directory [--server-out directory]

--only resolves and rewrites just those plugin/server names; the rest of the lock is kept.
--minecraft <serverVer> requires --only. Cap: newest jar whose declared game versions include
something ≤ that server version (e.g. server 1.21 → keep a 1.20-only plugin; skip a 26.3-only
build). Supported sources: modrinth, hangar, papermc, purpur.`)
  process.exit(2)
}

async function settle(entries: DesiredPlugin[]): Promise<{ locked: LockedArtifact[]; skipped: string[]; errors: string[] }> {
  const locked: LockedArtifact[] = []
  const skipped: string[] = []
  const errors: string[] = []
  async function one(entry: DesiredPlugin): Promise<void> {
    if (entry.source === "local") {
      skipped.push(entry.name)
      return
    }
    try {
      locked.push(await lockArtifact(entry))
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      errors.push(`${entry.name}: ${message}`)
    }
  }
  const remote = entries.filter((entry) => entry.source !== "git")
  const git = entries.filter((entry) => entry.source === "git")
  await Promise.all(remote.map((entry) => one(entry)))
  for (const entry of git) await one(entry)
  return { locked, skipped, errors }
}

function inRequestOrder(entries: DesiredPlugin[], locked: LockedArtifact[]): LockedArtifact[] {
  return entries.flatMap((entry) => {
    const match = locked.find((artifact) => artifact.name === entry.name && artifact.source === entry.source)
    return match ? [match] : []
  })
}

function desiredVersion(entry: DesiredPlugin): string | undefined {
  return entry.version ?? entry.commit ?? entry.build
}

/** Keep an existing lock row when the desired pin is unchanged (avoids Spiget/etc rehits). */
function reusePinned(entry: DesiredPlugin, previous: LockFile): LockedArtifact | undefined {
  const version = desiredVersion(entry)
  if (!version) return undefined
  const artifacts = [...(previous.plugins ?? []), ...(previous.server ? [previous.server] : [])]
  return artifacts.find((artifact) => {
    if (artifact.name !== entry.name || artifact.source !== entry.source || artifact.version !== version) {
      return false
    }
    if (entry.source === "git") {
      // Commit is the pin; no content hash. jar/java/builder must still match desired.
      if (entry.id && artifact.url !== entry.id) return false
      if ((entry.jar ?? "") !== (artifact.jar ?? "")) return false
      if ((entry.java ?? "") !== (artifact.java ?? "")) return false
      return true
    }
    if (!artifact.sha256 && !artifact.sha512 && !artifact.sha1) return false
    return true
  })
}

function mergeOnly(previous: LockFile, refreshed: LockedArtifact[], serverName: string | undefined): LockFile {
  // Match by name so --only can migrate sources (e.g. modrinth → git) without duplicating rows.
  const byName = new Map(refreshed.map((artifact) => [artifact.name, artifact]))
  const replace = (artifact: LockedArtifact | undefined): LockedArtifact | undefined => {
    if (!artifact) return undefined
    return byName.get(artifact.name) ?? artifact
  }
  const plugins = (previous.plugins ?? []).map((artifact) => replace(artifact)!)
  // New names from --only that were not in the lock yet (first pin).
  for (const artifact of refreshed) {
    if (serverName && artifact.name === serverName) {
      continue
    }
    const exists = plugins.some((row) => row.name === artifact.name)
    if (!exists) plugins.push(artifact)
  }
  return {
    server: replace(previous.server),
    plugins,
  }
}

async function update(
  desiredPath: string,
  lockPath: string,
  onlyNames: string[],
  minecraftCeiling: string | undefined,
): Promise<number> {
  const desired = await readDesired(desiredPath)
  const server = desired.server
    ? { ...desired.server, name: desired.server.name || desired.server.id || desired.server.source }
    : undefined
  const allEntries = [...(server ? [server] : []), ...desired.plugins].map((entry) =>
    entry.source === "git" ? { ...entry, serverMinecraft: desired.minecraft } : entry,
  )
  let previous: LockFile = { plugins: [] }
  try {
    previous = parse(await readFile(lockPath, "utf8")) as LockFile
  } catch {
    previous = { plugins: [] }
  }

  const only = onlyNames.map((name) => name.trim()).filter(Boolean)
  const ceiling = minecraftCeiling?.trim() || undefined
  if (ceiling && only.length === 0) {
    console.error("--minecraft requires --only (pass the server version as the ceiling, e.g. --minecraft 1.20.1)")
    return 1
  }
  if (only.length > 0) {
    const missing = only.filter((name) => !allEntries.some((entry) => entry.name === name))
    if (missing.length > 0) {
      for (const name of missing) console.error(`--only ${name}: not in ${desiredPath}`)
      return 1
    }
    if (!(previous.plugins?.length || previous.server)) {
      console.error(`--only needs an existing lock at ${lockPath}`)
      return 1
    }
  }

  const entries =
    only.length > 0 ? allEntries.filter((entry) => only.includes(entry.name)) : allEntries

  if (ceiling) {
    const unsupported = entries.filter((entry) => !MINECRAFT_CEILING_SOURCES.has(entry.source))
    if (unsupported.length > 0) {
      for (const entry of unsupported) {
        console.error(
          `--minecraft ${ceiling}: ${entry.name} source ${entry.source} has no per-jar Minecraft versions (use modrinth/hangar/papermc/purpur)`,
        )
      }
      return 1
    }
  }

  const toResolve = entries.map((entry) => (ceiling ? { ...entry, minecraftCeiling: ceiling } : entry))

  // With --only, always re-resolve those names (even if desired pins a version).
  const pending =
    only.length > 0 ? toResolve : toResolve.filter((entry) => !reusePinned(entry, previous))
  const reused =
    only.length > 0
      ? []
      : toResolve.flatMap((entry) => {
          const artifact = reusePinned(entry, previous)
          if (artifact) console.log(`reused ${artifact.name} ${artifact.version}`)
          return artifact ? [artifact] : []
        })
  const result = await settle(pending)
  result.locked.push(...reused)
  const ordered = inRequestOrder(entries, result.locked)
  const filenames = new Set<string>()
  for (const artifact of ordered) {
    if (filenames.has(artifact.filename)) {
      result.errors.push(`${artifact.name}: duplicate filename ${artifact.filename}`)
    }
    filenames.add(artifact.filename)
    console.log(compatibilityLine(desired.minecraft, artifact))
  }

  let lock: LockFile
  if (only.length > 0) {
    lock = mergeOnly(previous, ordered, server?.name)
  } else {
    // Keep previous rows for names that failed this run (private GH without token, etc.).
    // Never write a truncated lock that drops still-desired plugins.
    const previousByKey = new Map(
      [...(previous.plugins ?? []), ...(previous.server ? [previous.server] : [])].map((artifact) => [
        `${artifact.source}:${artifact.name}`,
        artifact,
      ]),
    )
    const merged: LockedArtifact[] = []
    for (const entry of entries) {
      const key = `${entry.source}:${entry.name}`
      const fresh = ordered.find((artifact) => artifact.name === entry.name && artifact.source === entry.source)
      const kept = fresh ?? previousByKey.get(key)
      if (kept) merged.push(kept)
    }
    lock = {
      server: server
        ? merged.find((artifact) => artifact.name === server.name && artifact.source === server.source)
        : undefined,
      plugins: merged.filter(
        (artifact) => !server || artifact.name !== server.name || artifact.source !== server.source,
      ),
    }
  }
  await writeFile(lockPath, stringify(lock))
  for (const name of result.skipped) console.log(`skipped local ${name}`)
  for (const message of result.errors) console.error(message)
  const pluginCount = lock.plugins.length
  const onlyNote = only.length ? ` (only ${only.join(", ")})` : ""
  const mcNote = ceiling ? ` [minecraft ≤ ${ceiling}]` : ""
  const keptNote = result.errors.length ? ` (${result.errors.length} kept from previous lock)` : ""
  console.log(`wrote ${lockPath}: ${pluginCount} plugins${lock.server ? " and server" : ""}${onlyNote}${mcNote}${keptNote}`)
  return result.errors.length === 0 ? 0 : 1
}

async function download(lockPath: string, outDir: string, serverOut?: string): Promise<number> {
  const { actionsCacheEnabled } = await import("./actions-cache.ts")
  console.log(
    actionsCacheEnabled()
      ? "actions-cache: enabled (per-entry plugin-jar-* keys)"
      : "actions-cache: disabled (no ACTIONS_RUNTIME_TOKEN / CACHE_URL / RESULTS_URL)",
  )
  const lock = parse(await readFile(lockPath, "utf8")) as LockFile
  const errors: string[] = []
  const jobs: { artifact: LockedArtifact; directory: string }[] = []
  for (const artifact of lock.plugins ?? []) jobs.push({ artifact, directory: outDir })
  if (lock.server) jobs.push({ artifact: lock.server, directory: serverOut ?? outDir })
  const remote = jobs.filter(({ artifact }) => artifact.source !== "git")
  const built = jobs.filter(({ artifact }) => artifact.source === "git")
  // Non-Spiget first (parallel), then git builds, then Spiget serial — so a Spiget
  // Cloudflare block does not start after wasting a PlotSquared compile, and warm
  // Actions hits for Spiget jars skip the network entirely.
  const spiget = remote.filter(({ artifact }) => artifact.source === "spiget")
  const otherRemote = remote.filter(({ artifact }) => artifact.source !== "spiget")
  await Promise.all(
    otherRemote.map(async ({ artifact, directory }) => {
      try {
        await downloadArtifact(artifact, directory)
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        errors.push(`${artifact.name}: ${message}`)
      }
    }),
  )
  for (const { artifact, directory } of built) {
    try {
      await downloadArtifact(artifact, directory)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      errors.push(`${artifact.name}: ${message}`)
    }
  }
  for (const { artifact, directory } of spiget) {
    try {
      await downloadArtifact(artifact, directory)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      errors.push(`${artifact.name}: ${message}`)
    }
  }
  for (const message of errors) console.error(message)
  return errors.length === 0 ? 0 : 1
}

const command = positionals[0]
if (command === "update") {
  if (!values.desired || !values.lock) usage()
  process.exit(await update(values.desired, values.lock, values.only ?? [], values.minecraft))
}
if (command === "download") {
  if (!values.lock || !values.out) usage()
  process.exit(await download(values.lock, values.out, values["server-out"]))
}
usage()
