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
  rm -rf "$DATA" "$LOG"
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
  -e "REDIS_URL=redis://redis:6379/0"
  -e "DATABASE_URL=postgres://misplease:smoke@db:5432/misplease"
  -e "JVM_OPTS=-Xms256M -Xmx${HEAP_MX} -XX:+UseG1GC -Dpaper.preferSparkPlugin=true"
  -v "$CONFIG_DIR:/config:ro"
  -v "$DATA:/data"
)

docker run "${run_args[@]}" "$IMAGE" >/dev/null

deadline=$((SECONDS + TIMEOUT_S))
while (( SECONDS < deadline )); do
  docker logs "$NAME" >"$LOG" 2>&1 || true
  if grep -q 'Done (' "$LOG"; then
    break
  fi
  if ! docker ps -q -f "name=^/${NAME}$" | grep -q .; then
    echo "container exited before Done" >&2
    cat "$LOG" >&2
    exit 1
  fi
  sleep 3
done

docker logs "$NAME" >"$LOG" 2>&1 || true
if ! grep -q 'Done (' "$LOG"; then
  echo "timed out after ${TIMEOUT_S}s waiting for Done (" >&2
  tail -n 80 "$LOG" >&2
  exit 1
fi

# Hard failures: a plugin did not enable.
if grep -E 'Error occurred while enabling |Unsupported server version detected:|Could not load [^\n]* plugin' "$LOG" >/tmp/smoke-errors.txt; then
  echo "smoke failed: plugin enable errors" >&2
  cat /tmp/smoke-errors.txt >&2
  exit 1
fi

echo "smoke ok: $IMAGE ($KIND)"
