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
# Port 25575 stays unpublished on the host; reach it only on the compose network.
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

# LuckPerms: inject DB/Redis passwords into the overlay copy (config mount is read-only).
# Placeholders live in git; never commit real secrets.
for lp_cfg in \
  "$LOWER/plugins/LuckPerms/config.yml" \
  "$LOWER/plugins/luckperms/config.yml"
do
  [ -f "$lp_cfg" ] || continue
  if [ -n "${DB_PASSWORD:-}" ]; then
    sed -i "s|__MISPLEASE_DB_PASSWORD__|${DB_PASSWORD}|g" "$lp_cfg"
  fi
  redis_pw="${REDIS_PASSWORD:-}"
  if [ -z "$redis_pw" ] && [ -n "${REDIS_URL:-}" ]; then
    # redis://:password@host:6379/0 → password (empty if URL has no auth)
    redis_pw="$(printf '%s' "$REDIS_URL" | sed -n 's|^redis://:\([^@]*\)@.*|\1|p')"
  fi
  # Always clear the placeholder (empty string is valid for unauthenticated Redis, e.g. smoke).
  sed -i "s|__MISPLEASE_REDIS_PASSWORD__|${redis_pw}|g" "$lp_cfg"
  rel="${lp_cfg#"$LOWER/"}"
  rm -f -- "$STATE/$rel"
done

if [ -n "$(find "$JARS" -mindepth 1 -maxdepth 1 -name '*.jar' -print -quit)" ]; then
  cp -a "$JARS"/*.jar "$LOWER/plugins/"
fi
find "$STATE/plugins" -mindepth 1 -maxdepth 1 \( -type f -o -type l \) -name '*.jar' -delete 2>/dev/null || true

if [ -z "$(find "$LOWER/plugins" -mindepth 1 -maxdepth 1 -name '*.jar' -print -quit)" ]; then
  echo "the image has no plugin jars" >&2
  exit 1
fi

# SMOKE_PLAIN=1: copy lower→state and run from /data/state (no overlay).
# GitHub Actions runners often lack usable kernel overlay; fuse-overlayfs then
# breaks plugin data dirs. Prod/preprod keep the real overlay path.
#
# Also slim world plugins: prod Multiverse/PlotSquared configs autoLoad dozens of
# worlds. On empty /data that means generating them all before Paper prints
# "Done (" — CI then times out with only the last ~80 log lines visible.
if [ "${SMOKE_PLAIN:-}" = "1" ]; then
  if [ "$KIND" != "proxy" ]; then
    mv_worlds="$LOWER/plugins/Multiverse-Core/worlds.yml"
    if [ -f "$mv_worlds" ]; then
      echo "smoke: Multiverse autoLoad=false (skip mass world gen)"
      # Quoted and bare YAML bools both appear in exported worlds.yml.
      sed -i -e "s/autoLoad: 'true'/autoLoad: 'false'/g" -e "s/autoLoad: true/autoLoad: false/g" "$mv_worlds"
    fi
    ps_worlds="$LOWER/plugins/PlotSquared/config/worlds.yml"
    if [ -f "$ps_worlds" ]; then
      echo "smoke: clearing PlotSquared worlds.yml"
      printf 'worlds: {}\n' >"$ps_worlds"
    fi
    # Daily restarter must not stop an empty smoke JVM (CI often runs near `at`).
    for restarter_cfg in \
      "$LOWER/plugins/backend-restarter/config.yml" \
      "$LOWER/plugins/Backend-Restarter/config.yml"
    do
      if [ -f "$restarter_cfg" ]; then
        echo "smoke: backend-restarter enabled=false"
        sed -i 's/^enabled:.*/enabled: false/' "$restarter_cfg"
      fi
    done
    # Slimefun stable auto-update + skin fetches can stall boot on GHA networks.
    sf_cfg="$LOWER/plugins/Slimefun/config.yml"
    if [ -f "$sf_cfg" ]; then
      echo "smoke: Slimefun auto-update=false"
      sed -i 's/^\([[:space:]]*\)auto-update:.*/\1auto-update: false/' "$sf_cfg"
    fi
  fi
  echo "smoke plain mode: copying server tree into $STATE"
  cp -a "$LOWER"/. "$STATE"/
  MERGED="$STATE"
else
  echo "mounting server overlay (upper=$STATE)"
  if ! mount -t overlay overlay -o "lowerdir=$LOWER,upperdir=$STATE,workdir=$WORK" "$MERGED"; then
    echo "kernel overlay failed, trying fuse-overlayfs" >&2
    rm -rf "$WORK"
    mkdir -p "$WORK"
    if ! fuse-overlayfs -o "lowerdir=$LOWER,upperdir=$STATE,workdir=$WORK" "$MERGED"; then
      echo "could not mount the server directory" >&2
      exit 1
    fi
    echo "mounted fuse-overlayfs on $MERGED"
  else
    echo "mounted kernel overlay on $MERGED"
  fi
fi

# Java drops to uid 1000; anything we copied/created as root must be writable.
chown -R 1000:1000 "$DATA" 2>/dev/null || true

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
