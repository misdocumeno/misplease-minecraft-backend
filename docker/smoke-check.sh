#!/usr/bin/env bash
# Boot a baked server/proxy image with empty state + git configs; fail on plugin load/enable errors.
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

# Heap stays small for CI. Non-heap flags match prod main today, plus preferSparkPlugin
# for the baked-image stack. Sources: docker-compose*.yml, .env.example, live prod cmdline.
JVM_OPTS="-Xms256M -Xmx${HEAP_MX} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -Dorg.maxgamer.quickshop.util.envcheck.skip.SIGNATURE_VERIFY -Dpaper.preferSparkPlugin=true --add-modules=jdk.incubator.vector"

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
  -e "JVM_OPTS=$JVM_OPTS"
  -v "$CONFIG_DIR:/config:ro"
  -v "$DATA:/data"
)

docker run "${run_args[@]}" "$IMAGE" >/dev/null

# Paper order (current): ServerLoadEvent(STARTUP) → delayed init → "Done (".
# backend-smoke-check logs [SmokeCheck] during ServerLoadEvent (before Done).
# Wait for both so we do not depend on that ordering staying forever.
deadline=$((SECONDS + TIMEOUT_S))
last_ping=0
while (( SECONDS < deadline )); do
  docker logs "$NAME" >"$LOG" 2>&1 || true
  done_ok=false
  smoke_ok=true
  grep -q 'Done (' "$LOG" && done_ok=true
  if [ "$KIND" = "server" ]; then
    smoke_ok=false
    grep -qE '\[SmokeCheck\] (OK|FAIL)' "$LOG" && smoke_ok=true
  fi
  if $done_ok && $smoke_ok; then
    break
  fi
  if ! docker ps -q -f "name=^/${NAME}$" | grep -q .; then
    echo "container exited before Done" >&2
    wc -l "$LOG" >&2 || true
    tail -n 200 "$LOG" >&2
    exit 1
  fi
  # Progress every ~15s so the Actions log is not silent for a minute at a time.
  if (( SECONDS - last_ping >= 15 )); then
    last_ping=$SECONDS
    lines="$(wc -l <"$LOG" | tr -d ' ')"
    echo "smoke waiting… ${SECONDS}s/${TIMEOUT_S}s log_lines=${lines} last:"
    tail -n 3 "$LOG" || true
  fi
  sleep 3
done

docker logs "$NAME" >"$LOG" 2>&1 || true
if ! grep -q 'Done (' "$LOG"; then
  echo "timed out after ${TIMEOUT_S}s waiting for Done (" >&2
  echo "---- log size ----" >&2
  wc -l "$LOG" >&2 || true
  echo "---- last 250 lines ----" >&2
  tail -n 250 "$LOG" >&2
  exit 1
fi

# Done ( does not mean every plugin loaded. The JVM has no plugin-load exit status;
# Paper keeps running and only logs. Match Paper logger names (stable) plus known phrases.
FAIL_RE='Error occurred while enabling |Unsupported server version detected:|Could not load |UnknownDependencyException|\[DirectoryProviderSource\]|\[ModernPluginLoadingStrategy\]|Directory .*\.jar. does not contain a (paper-)?plugin\.yml'
if grep -E "$FAIL_RE" "$LOG" >/tmp/smoke-errors.txt; then
  echo "smoke failed: plugin load/enable errors" >&2
  cat /tmp/smoke-errors.txt >&2
  echo "---- stack context ----" >&2
  awk -v re="$FAIL_RE" '
    $0 ~ re { show=40; print; next }
    show > 0 { print; show--; if ($0 ~ /^[[:space:]]*$/) show=0 }
  ' "$LOG" >&2
  echo "---- last 60 log lines ----" >&2
  tail -n 60 "$LOG" >&2
  exit 1
fi

# Final assertion (Paper only): backend-smoke-check scans jars vs PluginManager.
# Avoids brittle color parsing of /plugins; emits one grep-friendly line.
if [ "$KIND" = "server" ]; then
  if grep -q '\[SmokeCheck\] FAIL' "$LOG"; then
    echo "smoke failed: SmokeCheck reported plugin load failures" >&2
    grep '\[SmokeCheck\]' "$LOG" >&2 || true
    exit 1
  fi
  if ! grep -q '\[SmokeCheck\] OK' "$LOG"; then
    echo "smoke failed: missing [SmokeCheck] OK line (plugin not in image?)" >&2
    grep '\[SmokeCheck\]' "$LOG" >&2 || true
    exit 1
  fi
  grep '\[SmokeCheck\]' "$LOG" || true
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
