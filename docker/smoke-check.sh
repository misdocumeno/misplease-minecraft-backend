#!/usr/bin/env bash
# Boot a baked server/proxy image with empty state + git configs; fail on plugin enable errors.
set -euo pipefail

IMAGE="${1:?image tag}"
KIND="${2:?server|proxy}"
CONFIG_DIR="${3:?path to /config tree}"
HEAP_MX="${SMOKE_HEAP:-1536M}"
TIMEOUT_S="${SMOKE_TIMEOUT_S:-300}"

if [ ! -d "$CONFIG_DIR" ]; then
  echo "missing config dir: $CONFIG_DIR" >&2
  exit 1
fi
# Docker treats relative -v sources as volume names; always mount a host path.
CONFIG_DIR="$(cd "$CONFIG_DIR" && pwd)"

NET="misplease-smoke-$$"
NAME="misplease-smoke-$$"
REDIS="misplease-smoke-redis-$$"
DB="misplease-smoke-db-$$"
DATA="$(mktemp -d)"
LOG="$(mktemp)"

cleanup() {
  docker rm -f "$NAME" "$REDIS" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  # Bind-mounted /data is often owned by container uid 1000; never fail the job on cleanup.
  rm -rf "$DATA" "$LOG" 2>/dev/null || true
}
trap cleanup EXIT

docker network create "$NET" >/dev/null

docker run -d --name "$REDIS" --network "$NET" --network-alias redis \
  redis:8.10-alpine >/dev/null

docker run -d --name "$DB" --network "$NET" --network-alias db \
  -e POSTGRES_USER=misplease \
  -e POSTGRES_PASSWORD=smoke \
  -e POSTGRES_DB=misplease \
  postgres:18-alpine >/dev/null

for _ in $(seq 1 30); do
  if docker exec "$DB" pg_isready -U misplease -d misplease >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

run_args=(
  -d --name "$NAME" --network "$NET"
  --cap-add SYS_ADMIN
  --security-opt apparmor:unconfined
  --device /dev/fuse
  -e "KIND=$KIND"
  -e SERVER_ID=smoke
  -e SMOKE_PLAIN=1
  -e "REDIS_URL=redis://redis:6379/0"
  -e "DB_PASSWORD=smoke"
  -e "DATABASE_URL=postgres://misplease:smoke@db:5432/misplease"
  # SIGNATURE_VERIFY: own QuickShop build is unsigned; same flag as docker-compose main.
  -e "JVM_OPTS=-Xms256M -Xmx${HEAP_MX} -XX:+UseG1GC -Dpaper.preferSparkPlugin=true -Dorg.maxgamer.quickshop.util.envcheck.skip.SIGNATURE_VERIFY"
  -v "$CONFIG_DIR:/config:ro"
  -v "$DATA:/data"
)

docker run "${run_args[@]}" "$IMAGE" >/dev/null

# Paper: "Done (Xs)!" / "For help, type \"help\"". Velocity: "Done (Xs)!".
# backend-restarter also logs smoke-ready once the scheduler ticks (smoke only).
smoke_ready() {
  grep -qE 'Done \([0-9]|For help, type "help"|smoke-ready: server ticking' "$1"
}

deadline=$((SECONDS + TIMEOUT_S))
last_ping=0
while (( SECONDS < deadline )); do
  docker logs "$NAME" >"$LOG" 2>&1 || true
  if smoke_ready "$LOG"; then
    break
  fi
  if ! docker ps -q -f "name=^/${NAME}$" | grep -q .; then
    echo "container exited before smoke ready" >&2
    wc -l "$LOG" >&2 || true
    tail -n 200 "$LOG" >&2
    exit 1
  fi
  # Progress every ~60s so Actions UI isn't only redis/postgres pull + silence.
  if (( SECONDS - last_ping >= 60 )); then
    last_ping=$SECONDS
    lines="$(wc -l <"$LOG" | tr -d ' ')"
    echo "smoke waiting… ${SECONDS}s/${TIMEOUT_S}s log_lines=${lines} last:"
    tail -n 3 "$LOG" || true
  fi
  sleep 3
done

docker logs "$NAME" >"$LOG" 2>&1 || true
if ! smoke_ready "$LOG"; then
  echo "timed out after ${TIMEOUT_S}s waiting for smoke ready" >&2
  echo "---- log size ----" >&2
  wc -l "$LOG" >&2 || true
  echo "---- last 250 lines ----" >&2
  tail -n 250 "$LOG" >&2
  exit 1
fi

# Hard failures: a plugin did not enable.
if grep -E 'Error occurred while enabling |Unsupported server version detected:|Could not load [^\n]* plugin' "$LOG" >/tmp/smoke-errors.txt; then
  echo "smoke failed: plugin enable errors" >&2
  cat /tmp/smoke-errors.txt >&2
  echo "---- stack context ----" >&2
  # Print each enable-error line plus the following stack (until a blank/"Done"/next plugin line).
  awk '
    /Error occurred while enabling |Unsupported server version detected:|Could not load .* plugin/ { show=40; print; next }
    show > 0 { print; show--; if ($0 ~ /^[[:space:]]*$/) show=0 }
  ' "$LOG" >&2
  echo "---- last 60 log lines ----" >&2
  tail -n 60 "$LOG" >&2
  exit 1
fi

# Always surface warnings (Paper/Velocity/log4j). Do not fail the job on WARN alone.
echo "---- smoke WARN ----"
if grep -E '\[WARN\]|\[WARNING\]| WARN | WARNING ' "$LOG"; then
  :
else
  echo "(none)"
fi
echo "---- end smoke WARN ----"

echo "smoke ok: $IMAGE ($KIND)"
