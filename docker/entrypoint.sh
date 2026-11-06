#!/bin/bash
# The process works in /srv, a directory that exists only inside the container.
# /srv is an overlay: config and plugin jars come from the image, and anything
# the server writes lands in /data/state on the host. /config is a read-only
# mount and is never copied onto that host directory.
set -euo pipefail

DATA="${DATA_DIR:-/data}"
STATE="$DATA/state"
WORK="$DATA/.overlay-work"
LOWER=/run/misplease-lower
MERGED=/srv
JARS=/opt/misplease/jars
KIND="${KIND:-server}"

mkdir -p "$STATE" "$MERGED"
rm -rf "$LOWER" "$WORK"
mkdir -p "$LOWER/plugins" "$WORK"

if [ -d /config ]; then
  cp -a /config/. "$LOWER/"
  while IFS= read -r -d '' file; do
    rel="${file#/config/}"
    rm -f -- "$STATE/$rel"
  done < <(find /config -type f -print0)
fi

if [ "$KIND" != "proxy" ]; then
  printf 'eula=true\n' > "$LOWER/eula.txt"
  rm -f -- "$STATE/eula.txt"
fi

# Optional console access without publishing RCON: set RCON_PASSWORD in compose/.env.
# Port 25575 stays unpublished; tools/mc-console.sh reaches it via the container netns.
# Prefer a random password in .env — sibling containers on the compose network can still dial RCON.
if [ -n "${RCON_PASSWORD:-}" ] && [ -f "$LOWER/server.properties" ]; then
  sed -i 's/^enable-rcon=.*/enable-rcon=true/' "$LOWER/server.properties"
  if grep -q '^rcon.password=' "$LOWER/server.properties"; then
    sed -i "s/^rcon.password=.*/rcon.password=${RCON_PASSWORD}/" "$LOWER/server.properties"
  else
    printf 'rcon.password=%s\n' "$RCON_PASSWORD" >> "$LOWER/server.properties"
  fi
  rm -f -- "$STATE/server.properties"
fi

if [ -n "$(find "$JARS" -mindepth 1 -maxdepth 1 -name '*.jar' -print -quit)" ]; then
  cp -a "$JARS"/*.jar "$LOWER/plugins/"
fi
find "$STATE/plugins" -mindepth 1 -maxdepth 1 \( -type f -o -type l \) -name '*.jar' -delete 2>/dev/null || true

chown 1000:1000 "$STATE" "$WORK" 2>/dev/null || true

if [ -z "$(find "$LOWER/plugins" -mindepth 1 -maxdepth 1 -name '*.jar' -print -quit)" ]; then
  echo "the image has no plugin jars" >&2
  exit 1
fi

# SMOKE_PLAIN=1: copy lower→state and run from /data/state (no overlay).
# GitHub Actions runners often lack usable kernel overlay; fuse-overlayfs then
# breaks plugin data dirs. Prod/preprod keep the real overlay path.
if [ "${SMOKE_PLAIN:-}" = "1" ]; then
  echo "smoke plain mode: copying server tree into $STATE"
  cp -a "$LOWER"/. "$STATE"/
  MERGED="$STATE"
else
  echo "mounting server overlay (upper=$STATE)"
  if ! mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$STATE,workdir=$WORK" "$MERGED"; then
    echo "kernel overlay failed, trying fuse-overlayfs" >&2
    rm -rf "$WORK"
    mkdir -p "$WORK"
    chown 1000:1000 "$WORK" 2>/dev/null || true
    if ! fuse-overlayfs -o "lowerdir=$LOWER,upperdir=$STATE,workdir=$WORK" "$MERGED"; then
      echo "could not mount the server directory" >&2
      exit 1
    fi
    echo "mounted fuse-overlayfs on $MERGED"
  else
    echo "mounted kernel overlay on $MERGED"
  fi
fi

server_jar="$(find /opt/misplease/server -maxdepth 1 -type f -name '*.jar' -print -quit)"
if [ -z "$server_jar" ]; then
  echo "the image has no server jar" >&2
  exit 1
fi

args=()
if [ "$KIND" != "proxy" ]; then
  args+=(--nogui)
fi

cd "$MERGED"
echo "starting $server_jar"
if id -u 1000 >/dev/null 2>&1; then
  exec setpriv --reuid=1000 --regid=1000 --init-groups --inh-caps=-all \
    java ${JVM_OPTS:-} -jar "$server_jar" "${args[@]}"
fi
exec java ${JVM_OPTS:-} -jar "$server_jar" "${args[@]}"
