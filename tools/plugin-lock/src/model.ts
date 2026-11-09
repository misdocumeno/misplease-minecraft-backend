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
  /** Exact filename/path or `/regex/` (YAML has no regex type). Git: matched against repo-relative path and basename. */
  jar?: string
  server?: string
  java?: string
  release?: string
  serverMinecraft?: string
  /** When set (via update --minecraft), resolvers pick the newest jar that still fits this server version. */
  minecraftCeiling?: string
  /** Modrinth loaders to accept (default: paper/purpur/bukkit/spigot). Use `[velocity]` on the proxy. */
  loaders?: string[]
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
  /** Git: jar selector carried into download (exact or `/regex/`). */
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
  builder?: string
  java?: string
  release?: string
}
