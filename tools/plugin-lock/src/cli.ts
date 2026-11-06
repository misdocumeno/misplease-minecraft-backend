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
  },
  allowPositionals: true,
})

function usage(): never {
  console.error(`plugin-lock update --desired desired.yml --lock lock.yml [--only name ...]
plugin-lock download --lock lock.yml --out directory [--server-out directory]

--only resolves and rewrites just those plugin/server names; the rest of the lock is kept.
Use after releasing one of our plugins so prod picks up the new GitHub asset without
refreshing every third-party pin.`)
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
    if (!artifact.sha256 && !artifact.sha512 && !artifact.sha1) return false
    if (entry.source === "git" && entry.id && artifact.url !== entry.id) return false
    return true
  })
}

function mergeOnly(previous: LockFile, refreshed: LockedArtifact[], serverName: string | undefined): LockFile {
  const byKey = new Map(refreshed.map((artifact) => [`${artifact.source}:${artifact.name}`, artifact]))
  const replace = (artifact: LockedArtifact | undefined): LockedArtifact | undefined => {
    if (!artifact) return undefined
    return byKey.get(`${artifact.source}:${artifact.name}`) ?? artifact
  }
  const plugins = (previous.plugins ?? []).map((artifact) => replace(artifact)!)
  // New names from --only that were not in the lock yet (first pin).
  for (const artifact of refreshed) {
    if (serverName && artifact.name === serverName && artifact.source === (previous.server?.source ?? artifact.source)) {
      continue
    }
    const exists = plugins.some((row) => row.name === artifact.name && row.source === artifact.source)
    if (!exists) plugins.push(artifact)
  }
  return {
    server: replace(previous.server),
    plugins,
  }
}

async function update(desiredPath: string, lockPath: string, onlyNames: string[]): Promise<number> {
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

  // With --only, always re-resolve those names (even if desired pins a version).
  const pending =
    only.length > 0 ? entries : entries.filter((entry) => !reusePinned(entry, previous))
  const reused =
    only.length > 0
      ? []
      : entries.flatMap((entry) => {
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
    lock = {
      server: server ? ordered.find((artifact) => artifact.name === server.name && artifact.source === server.source) : undefined,
      plugins: ordered.filter((artifact) => !server || artifact.name !== server.name || artifact.source !== server.source),
    }
  }
  await writeFile(lockPath, stringify(lock))
  for (const name of result.skipped) console.log(`skipped local ${name}`)
  for (const message of result.errors) console.error(message)
  const pluginCount = lock.plugins.length
  console.log(`wrote ${lockPath}: ${pluginCount} plugins${lock.server ? " and server" : ""}${only.length ? ` (only ${only.join(", ")})` : ""}`)
  return result.errors.length === 0 ? 0 : 1
}

async function download(lockPath: string, outDir: string, serverOut?: string): Promise<number> {
  const lock = parse(await readFile(lockPath, "utf8")) as LockFile
  const errors: string[] = []
  const jobs: { artifact: LockedArtifact; directory: string }[] = []
  for (const artifact of lock.plugins ?? []) jobs.push({ artifact, directory: outDir })
  if (lock.server) jobs.push({ artifact: lock.server, directory: serverOut ?? outDir })
  const remote = jobs.filter(({ artifact }) => artifact.source !== "git")
  const built = jobs.filter(({ artifact }) => artifact.source === "git")
  // Spiget rate-limits parallel CI downloads; keep those serial, parallelize the rest.
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
  for (const { artifact, directory } of spiget) {
    try {
      await downloadArtifact(artifact, directory)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      errors.push(`${artifact.name}: ${message}`)
    }
  }
  for (const { artifact, directory } of built) {
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
  process.exit(await update(values.desired, values.lock, values.only ?? []))
}
if (command === "download") {
  if (!values.lock || !values.out) usage()
  process.exit(await download(values.lock, values.out, values["server-out"]))
}
usage()
