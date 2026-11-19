# plugin-lock

`plugins.yml` is the **desired** set (which plugin, which version). `plugins-lock.yml`
is the **resolved** set (exact file, URL, hash) that the image build bakes into a
server. Only the lock is consumed by CI/prod.

## Adding a plugin

1. Add the entry to the right `plugins.yml`:
   - `infra/velocity/plugins.yml` for the proxy
   - `infra/minecraft/main/plugins.yml` for main
   - `infra/minecraft/tech/plugins.yml` for tech

   Prefer `source: modrinth` when the project exists there; Hangar/Spigot/GitHub
   are fallbacks. Pin `version:` only when you need to (a version line/build, or a
   known-good pin); otherwise omit it so the lock takes the newest release.

2. Resolve just that name (never the whole file, unless you intend to drift every
   other pin):

   ```bash
   cd tools/plugin-lock
   node --experimental-strip-types src/cli.ts update \
     --desired ../../infra/velocity/plugins.yml \
     --lock ../../infra/velocity/plugins-lock.yml \
     --only LimboAPI
   ```

   `--only` also works for a name that is not in the lock yet (first pin), and for
   changing a plugin's `source` (e.g. `spiget` → `modrinth`) without duplicating it.
   Pass `--only` once per name to update several.

3. Commit **both** files and push. The `images` workflow rebuilds only the cells
   whose `plugins-lock.yml` changed, then smoke boots them.

## Removing a plugin

Delete the entry from `plugins.yml` and run the same `update` command. The lock
row disappears; the next image no longer contains the jar. Runtime data under
`infra/**/data/` is untouched.

## Rules

- Never copy a jar by hand onto the VM, into `infra/**/data/`, or into the image.
  A jar that is not in the lock does not exist as far as prod is concerned.
- Never edit `plugins-lock.yml` by hand. It is generated.
- A plugin that is a hard `@Plugin(dependencies = ...)` requirement must be added
  here first; the proxy/server will otherwise refuse to load the dependent plugin
  (that is intentional, and the smoke gate catches it before prod).
- Re-uploading an asset under an existing tag gives it a new asset id and breaks
  the lock; cut a new tag and re-run `--only`.
- Own plugins are **not** in the lock: their jars are compiled from `plugins/`
  during the image build (`PLUGIN_PREFIX`).
