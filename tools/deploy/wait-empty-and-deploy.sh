#!/usr/bin/env bash
# Wait until Redis presence reports proxy empty for EMPTY_SECONDS, then
# enable Velocity maintenance (block new joins), hard-reset checkout to DEPLOY_SHA,
# docker compose pull + up, then clear maintenance.
#
# Empty age uses presence.updatedAt while empty=true — if the proxy has already
# been empty longer than EMPTY_SECONDS when the job starts, deploy arms immediately
# (no extra full grace). A join resets the presence timestamp and the grace.
#
# During the empty grace, players MAY join and abort the grace (deploy stays
# pending). Maintenance turns on only after the grace succeeds.
#
# Runner lives in compose (docker.sock) on the same network as redis — Redis is
# the only gate (no presence.json). Schema: see velocity-presence Presence DTO.
set -euo pipefail

ROOT="${MISPLEASE_ROOT:-/home/mis/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
EMPTY_SECONDS="${EMPTY_SECONDS:-60}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-0}"
DEPLOY_SHA="${DEPLOY_SHA:-}"
MAINTENANCE_KEY="${MAINTENANCE_KEY:-misplease:maintenance}"
MAINTENANCE_VALUE="${MAINTENANCE_VALUE:-{\"enabled\":true,\"message\":\"&cServidor en mantenimiento. Probá de nuevo en unos minutos.\"}}"

if [[ -z "${REDIS_URL:-}" ]]; then
  if [[ -n "${REDIS_PASSWORD:-}" ]]; then
    # redis-cli requires an ACL username; empty-user URLs (redis://:pass@) AUTH-fail.
    REDIS_URL="redis://default:${REDIS_PASSWORD}@redis:6379/0"
  else
    echo "REDIS_URL or REDIS_PASSWORD required (compose runner)" >&2
    exit 1
  fi
fi
# Normalize app-style redis://:pass@host → redis://default:pass@host for redis-cli.
if [[ "$REDIS_URL" =~ ^redis://:([^@]*)@(.+)$ ]]; then
  REDIS_URL="redis://default:${BASH_REMATCH[1]}@${BASH_REMATCH[2]}"
fi
export REDIS_URL

redis_cli() {
  redis-cli --no-auth-warning "$@"
}

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
    redis_cli -u "$REDIS_URL" DEL "$MAINTENANCE_KEY" >/dev/null || true
    maintenance_on=0
  fi
}
trap clear_maintenance EXIT

started="$(date +%s)"
last_age_reported=""

# Prints integer seconds the proxy has been empty (from presence.updatedAt), or fails.
empty_age_seconds() {
  local payload
  payload="$(redis_cli -u "$REDIS_URL" --raw GET misplease:presence:proxy || true)"
  [[ -n "$payload" ]] || return 1
  if command -v python3 >/dev/null 2>&1; then
    PRESENCE_JSON="$payload" python3 - <<'PY'
import json, os, sys
from datetime import datetime, timezone
p = json.loads(os.environ["PRESENCE_JSON"])
if p.get("empty") is not True or int(p.get("count") or 0) != 0:
    sys.exit(1)
raw = p.get("updatedAt") or ""
try:
    # Kotlin Instant / ISO-8601, optionally with nanos
    s = raw.replace("Z", "+00:00")
    if "." in s:
        head, rest = s.split(".", 1)
        frac = ""
        tz = "+00:00"
        for i, ch in enumerate(rest):
            if ch.isdigit():
                frac += ch
            else:
                tz = rest[i:]
                break
        frac = (frac + "000000")[:6]
        s = f"{head}.{frac}{tz}"
    dt = datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    age = int((datetime.now(timezone.utc) - dt).total_seconds())
    if age < 0:
        age = 0
    print(age)
except Exception:
    sys.exit(1)
PY
  else
    PRESENCE_JSON="$payload" node -e '
const p = JSON.parse(process.env.PRESENCE_JSON);
if (p.empty !== true || (p.count|0) !== 0) process.exit(1);
const t = Date.parse(p.updatedAt);
if (!Number.isFinite(t)) process.exit(1);
let age = Math.floor((Date.now() - t) / 1000);
if (age < 0) age = 0;
process.stdout.write(String(age));
'
  fi
}

echo "waiting for proxy empty ≥ ${EMPTY_SECONDS}s (presence.updatedAt while empty)"

while true; do
  now="$(date +%s)"
  if [[ "$MAX_WAIT_SECONDS" -gt 0 && $((now - started)) -ge "$MAX_WAIT_SECONDS" ]]; then
    echo "timed out after ${MAX_WAIT_SECONDS}s still not empty" >&2
    exit 1
  fi

  if age="$(empty_age_seconds)"; then
    if [[ "$last_age_reported" != "$age" ]]; then
      echo "proxy empty for ${age}s (need ${EMPTY_SECONDS}s)"
      last_age_reported="$age"
    fi
    if [[ "$age" -ge "$EMPTY_SECONDS" ]]; then
      echo "proxy empty for ${age}s — arming deploy"
      break
    fi
  else
    if [[ -n "$last_age_reported" ]]; then
      echo "player activity — grace reset (pending deploy still armed)"
    fi
    last_age_reported=""
  fi
  sleep "$POLL_SECONDS"
done

echo "enabling maintenance (deny joins)"
redis_cli -u "$REDIS_URL" SET "$MAINTENANCE_KEY" "$MAINTENANCE_VALUE" >/dev/null
maintenance_on=1

# Brief re-check: someone may have slipped in during the last poll interval.
sleep 2
if ! empty_age_seconds >/dev/null; then
  echo "players present after maintenance arm — clearing maintenance and aborting this attempt" >&2
  clear_maintenance
  exit 1
fi

if [[ -n "$DEPLOY_SHA" ]] && [[ -d .git ]]; then
  echo "git fetch + hard reset $DEPLOY_SHA"
  # Runner container uid ≠ host checkout owner (docker bind-mount).
  git config --global --add safe.directory "$ROOT"
  # Deploy key is mounted at /root/.ssh (see github-runner service).
  export GIT_SSH_COMMAND="${GIT_SSH_COMMAND:-ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new}"
  # Shallow fetch of a SHA often has no merge-base with the previous shallow
  # tip ("refusing to merge unrelated histories"). Deploy trees are disposable:
  # pin exactly to DEPLOY_SHA (.env / data dirs stay untracked).
  git fetch --depth=1 origin "$DEPLOY_SHA"
  git reset --hard FETCH_HEAD
fi

# Only game/auth services — never recreate github-runner/backup mid-deploy.
SERVICES="velocity main tech auth db-migrate db redis"

echo "docker compose -f $COMPOSE_FILE pull $SERVICES"
docker compose -f "$COMPOSE_FILE" pull $SERVICES || docker compose -f "$COMPOSE_FILE" pull

echo "docker compose -f $COMPOSE_FILE up -d $SERVICES"
docker compose -f "$COMPOSE_FILE" up -d $SERVICES

echo "deploy finished — clearing maintenance"
clear_maintenance
trap - EXIT

echo "done"
docker compose -f "$COMPOSE_FILE" ps
