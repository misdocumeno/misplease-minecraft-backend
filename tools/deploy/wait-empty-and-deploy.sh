#!/usr/bin/env bash
# Wait until Redis presence reports proxy empty for EMPTY_SECONDS, then
# enable Velocity maintenance (block new joins), ff-only update checkout,
# docker compose pull + up, then clear maintenance.
#
# During the empty grace, players MAY join and abort the grace (deploy stays
# pending). Maintenance turns on only after the grace succeeds.
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
MAINTENANCE_KEY="${MAINTENANCE_KEY:-misplease:maintenance}"
MAINTENANCE_VALUE="${MAINTENANCE_VALUE:-{\"enabled\":true,\"message\":\"&cServidor en mantenimiento. Probá de nuevo en unos minutos.\"}}"

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

maintenance_on=0
clear_maintenance() {
  if [[ "$maintenance_on" -eq 1 ]]; then
    echo "clearing maintenance ($MAINTENANCE_KEY)"
    redis-cli -u "$REDIS_URL" DEL "$MAINTENANCE_KEY" >/dev/null || true
    maintenance_on=0
  fi
}
trap clear_maintenance EXIT

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
      echo "proxy empty — starting ${EMPTY_SECONDS}s reconnect grace (joins still allowed)"
    fi
    waited=$((now - empty_since))
    if [[ "$waited" -ge "$EMPTY_SECONDS" ]]; then
      echo "proxy empty for ${waited}s — arming deploy"
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

echo "enabling maintenance (deny joins)"
redis-cli -u "$REDIS_URL" SET "$MAINTENANCE_KEY" "$MAINTENANCE_VALUE" >/dev/null
maintenance_on=1

# Brief re-check: someone may have slipped in during the last poll interval.
sleep 2
if ! is_empty; then
  echo "players present after maintenance arm — clearing maintenance and aborting this attempt" >&2
  clear_maintenance
  exit 1
fi

if [[ -n "$DEPLOY_SHA" ]] && [[ -d .git ]]; then
  echo "git fetch + ff-only merge $DEPLOY_SHA"
  git fetch --depth=1 origin "$DEPLOY_SHA"
  git merge --ff-only "$DEPLOY_SHA"
fi

# Only game/auth services — never recreate github-runner/backup mid-deploy.
SERVICES="velocity main tech auth auth-db-migrate db redis"

echo "docker compose -f $COMPOSE_FILE pull $SERVICES"
docker compose -f "$COMPOSE_FILE" pull $SERVICES || docker compose -f "$COMPOSE_FILE" pull

echo "docker compose -f $COMPOSE_FILE up -d $SERVICES"
docker compose -f "$COMPOSE_FILE" up -d $SERVICES

echo "deploy finished — clearing maintenance"
clear_maintenance
trap - EXIT

echo "done"
docker compose -f "$COMPOSE_FILE" ps
