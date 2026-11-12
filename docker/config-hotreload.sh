#!/bin/sh
# Sync allowlisted plugin configs from the read-only /config mount into the
# overlay upper dir so plugins see host edits without a container recreate.
# Pair with in-plugin watchers (BetterWarps ConfigFileWatcher first).
set -eu

STATE="${DATA_DIR:-/data}/state"
# Space-separated plugin folder names under plugins/. Empty = disabled.
ALLOWLIST="${CONFIG_HOTRELOAD_PLUGINS:-BetterWarps}"
INTERVAL_S="${CONFIG_HOTRELOAD_INTERVAL_S:-2}"
HASH_DIR="/tmp/misplease-config-hotreload-hashes"

if [ -z "$ALLOWLIST" ] || [ ! -d /config ]; then
  exit 0
fi

mkdir -p "$HASH_DIR"
echo "config hot-reload watching: $ALLOWLIST (every ${INTERVAL_S}s)"

hash_tree() {
  dir="$1"
  if [ ! -d "$dir" ]; then
    echo ""
    return
  fi
  find "$dir" -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | awk '{print $1}'
}

sync_plugin() {
  name="$1"
  src="/config/plugins/$name"
  dest="$STATE/plugins/$name"
  [ -d "$src" ] || return 0
  mkdir -p "$dest"
  # Overlay: writing to upper ($STATE) makes the merged /srv tree pick up host edits.
  cp -a "$src"/. "$dest"/
  echo "[config-hotreload] synced $name → $dest"
}

while true; do
  for name in $ALLOWLIST; do
    src="/config/plugins/$name"
    h="$(hash_tree "$src")"
    hash_file="$HASH_DIR/$name"
    prev=""
    [ -f "$hash_file" ] && prev="$(cat "$hash_file")"
    if [ -n "$h" ] && [ "$h" != "$prev" ]; then
      if [ -n "$prev" ]; then
        sync_plugin "$name"
      fi
      printf '%s\n' "$h" > "$hash_file"
    fi
  done
  sleep "$INTERVAL_S"
done
