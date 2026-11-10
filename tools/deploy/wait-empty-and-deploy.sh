#!/usr/bin/env bash
# Wait until the proxy is empty for EMPTY_SECONDS, then ff-only update the stack
# checkout (optional) and docker compose pull + up.
# Intended for a self-hosted Actions runner (prefer compose service + docker.sock).
#
# Presence SoT is Redis key misplease:presence:proxy (same JSON schema as the file).
# presence.json is only a host mirror for humans / fallback when REDIS_URL is unset.
set -euo pipefail

ROOT="${MISPLEASE_ROOT:-/home/mis/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
PRESENCE_FILE="${PRESENCE_FILE:-$ROOT/infra/velocity/data/presence.json}"
EMPTY_SECONDS="${EMPTY_SECONDS:-60}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-0}"
DEPLOY_SHA="${DEPLOY_SHA:-}"
REDIS_URL="${REDIS_URL:-}"

cd "$ROOT"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "missing compose file: $ROOT/$COMPOSE_FILE" >&2
  exit 1
fi

empty_since=""
started="$(date +%s)"

is_empty_payload() {
  local payload="$1"
  if command -v python3 >/dev/null 2>&1; then
    PRESENCE_JSON="$payload" python3 - <<'PY'
import json, os, sys
p = json.loads(os.environ["PRESENCE_JSON"])
sys.exit(0 if p.get("empty") is True and int(p.get("count") or 0) == 0 else 1)
PY
  elif command -v node >/dev/null 2>&1; then
    PRESENCE_JSON="$payload" node -e 'const p=JSON.parse(process.env.PRESENCE_JSON); process.exit(p.empty===true && (p.count|0)===0?0:1)'
  else
    echo "need python3 or node to parse presence JSON" >&2
    exit 1
  fi
}

read_presence() {
  if [[ -n "$REDIS_URL" ]]; then
    # redis-cli -u redis://:pass@host:6379/0 GET key
    if ! command -v redis-cli >/dev/null 2>&1; then
      echo "REDIS_URL set but redis-cli missing" >&2
      exit 1
    fi
    redis-cli -u "$REDIS_URL" --raw GET misplease:presence:proxy
  else
    if [[ ! -f "$PRESENCE_FILE" ]]; then
      echo "presence file missing: $PRESENCE_FILE (and REDIS_URL unset)" >&2
      return 1
    fi
    cat "$PRESENCE_FILE"
  fi
}

is_empty() {
  local payload
  payload="$(read_presence)" || return 1
  [[ -n "$payload" ]] || return 1
  is_empty_payload "$payload"
}

if [[ -n "$REDIS_URL" ]]; then
  echo "waiting for proxy empty ≥ ${EMPTY_SECONDS}s (redis misplease:presence:proxy)"
else
  echo "waiting for proxy empty ≥ ${EMPTY_SECONDS}s (file $PRESENCE_FILE)"
fi

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
