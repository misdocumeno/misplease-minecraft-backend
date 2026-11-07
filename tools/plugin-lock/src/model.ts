export interface DesiredPlugin {
  name: string
  source: string
  id?: string
  version?: string
  build?: string
  asset?: string
  channel?: string
  commit?: string
  builder?: string
  task?: string
  /** Select a jar by exact filename or `/regex/` (GitHub, Jenkins, Modrinth, and git build outputs). */
  jar?: string
  /** Git only: relative path inside the repo to the built jar (preferred over guessing). */
  jarPath?: string
  server?: string
  java?: string
  release?: string
  serverMinecraft?: string
  /** When set (via update --minecraft), resolvers pick the newest jar that still fits this server version. */
  minecraftCeiling?: string
}

export interface DesiredFile {
  minecraft?: string
  server?: DesiredPlugin
  plugins: DesiredPlugin[]
}

export interface LockedArtifact {
  name: string
  source: string
  version: string
  filename: string
  url: string
  sha256?: string
  sha512?: string
  sha1?: string
  minecraft?: string[]
  task?: string
  /** Git: relative path of the jar that was built (lock field `jarPath`). */
  jarPath?: string
  /** Legacy git path field; prefer `jarPath`. */
  jar?: string
  builder?: string
  java?: string
  release?: string
}

export interface LockFile {
  server?: LockedArtifact
  plugins: LockedArtifact[]
}

export interface ResolvedArtifact {
  name: string
  source: string
  version: string
  filename: string
  url: string
  minecraft?: string[]
  sha256?: string
  sha512?: string
  sha1?: string
  task?: string
  jar?: string
  jarPath?: string
  builder?: string
  java?: string
  release?: string
}
