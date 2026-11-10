#!/usr/bin/env bash
# Wait until Redis presence reports proxy empty for EMPTY_SECONDS, then
# ff-only update the stack checkout (optional) and docker compose pull + up.
#
# Runner lives in compose (docker.sock) on the same network as redis — Redis is
# the only gate (no presence.json). Schema: see velocity-presence Presence DTO.
set -euo pipefail

ROOT="${MISPLEASE_ROOT:-/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
EMPTY_SECONDS="${EMPTY_SECONDS:-60}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-0}"
DEPLOY_SHA="${DEPLOY_SHA:-}"

if [[ -z "${REDIS_URL:-}" ]]; then
  if [[ -n "${REDIS_PASSWORD:-}" ]]; then
    REDIS_URL="redis://:${REDIS_PASSWORD}@redis:6379/0"
  else
    echo "REDIS_URL or REDIS_PASSWORD required (compose runner)" >&2
    exit 1
  fi
fi
export REDIS_URL

cd "$ROOT"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "missing compose file: $ROOT/$COMPOSE_FILE" >&2
  exit 1
fi

if ! command -v redis-cli >/dev/null 2>&1; then
  echo "redis-cli required" >&2
  exit 1
fi

empty_since=""
started="$(date +%s)"

is_empty() {
  local payload
  payload="$(redis-cli -u "$REDIS_URL" --raw GET misplease:presence:proxy || true)"
  [[ -n "$payload" ]] || return 1
  if command -v python3 >/dev/null 2>&1; then
    PRESENCE_JSON="$payload" python3 - <<'PY'
import json, os, sys
p = json.loads(os.environ["PRESENCE_JSON"])
sys.exit(0 if p.get("empty") is True and int(p.get("count") or 0) == 0 else 1)
PY
  else
    PRESENCE_JSON="$payload" node -e 'const p=JSON.parse(process.env.PRESENCE_JSON); process.exit(p.empty===true && (p.count|0)===0?0:1)'
  fi
}

echo "waiting for proxy empty ≥ ${EMPTY_SECONDS}s (redis misplease:presence:proxy)"

while true; do
  now="$(date +%s)"
  if [[ "$MAX_WAIT_SECONDS" -gt 0 && $((now - started)) -ge "$MAX_WAIT_SECONDS" ]]; then
    echo "timed out after ${MAX_WAIT_SECONDS}s still not empty" >&2
    exit 1
  fi

  if is_empty; then
    if [[ -z "$empty_since" ]]; then
      empty_since="$now"
      echo "proxy empty — starting ${EMPTY_SECONDS}s reconnect grace"
    fi
    waited=$((now - empty_since))
    if [[ "$waited" -ge "$EMPTY_SECONDS" ]]; then
      echo "proxy empty for ${waited}s — deploying"
      break
    fi
  else
    if [[ -n "$empty_since" ]]; then
      echo "player activity — grace reset (pending deploy still armed)"
    fi
    empty_since=""
  fi
  sleep "$POLL_SECONDS"
done

if [[ -n "$DEPLOY_SHA" ]] && [[ -d .git ]]; then
  echo "git fetch + ff-only merge $DEPLOY_SHA"
  git fetch --depth=1 origin "$DEPLOY_SHA"
  git merge --ff-only "$DEPLOY_SHA"
fi

echo "docker compose -f $COMPOSE_FILE pull"
docker compose -f "$COMPOSE_FILE" pull

echo "docker compose -f $COMPOSE_FILE up -d"
docker compose -f "$COMPOSE_FILE" up -d

echo "deploy finished"
docker compose -f "$COMPOSE_FILE" ps
