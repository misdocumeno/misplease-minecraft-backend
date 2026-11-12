#!/usr/bin/env bash
# Wait until Redis presence reports proxy empty for EMPTY_SECONDS, then
# enable Velocity maintenance (block new joins), hard-reset checkout to DEPLOY_SHA,
# docker compose pull + up, post-deploy health, save last-good / rollback on failure,
# then clear maintenance.
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
#
# Last-good: $ROOT/.deploy-last-good.json (gitignored). On successful health,
# records git sha + image digests. On health failure, resets to that snapshot
# (IMAGE_TAG=sha — images.yml also pushes :$sha) and re-ups. Alert via
# DEPLOY_ALERT_WEBHOOK (Discord-compatible JSON {"content":"..."}) or stdout.
set -euo pipefail

ROOT="${MISPLEASE_ROOT:-/home/mis/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
EMPTY_SECONDS="${EMPTY_SECONDS:-60}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-0}"
DEPLOY_SHA="${DEPLOY_SHA:-}"
HEALTH_TIMEOUT_S="${HEALTH_TIMEOUT_S:-180}"
MAINTENANCE_KEY="${MAINTENANCE_KEY:-misplease:maintenance}"
MAINTENANCE_VALUE="${MAINTENANCE_VALUE:-{\"enabled\":true,\"message\":\"&cServidor en mantenimiento. Probá de nuevo en unos minutos.\"}}"
LAST_GOOD_FILE="${LAST_GOOD_FILE:-$ROOT/.deploy-last-good.json}"
SERVICES="velocity main tech auth db-migrate db redis"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEALTH_SCRIPT="${HEALTH_SCRIPT:-$SCRIPT_DIR/post-deploy-health.sh}"

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

alert() {
  local msg="$1"
  echo "DEPLOY ALERT: $msg" >&2
  if [[ -n "${DEPLOY_ALERT_WEBHOOK:-}" ]]; then
    # Discord incoming webhook accepts {"content":"..."}; other hooks can adapt later.
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "$(printf '%s' "$msg" | python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()[:1800]}))')" \
      "$DEPLOY_ALERT_WEBHOOK" >/dev/null || echo "alert webhook failed" >&2
  fi
}

read_last_good_sha() {
  if [[ ! -f "$LAST_GOOD_FILE" ]]; then
    return 1
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sha",""))' "$LAST_GOOD_FILE"
  else
    node -e 'const j=require(process.argv[1]); process.stdout.write(j.sha||"")' "$LAST_GOOD_FILE"
  fi
}

image_digest() {
  local svc="$1"
  local cid
  cid="$(docker compose -f "$COMPOSE_FILE" ps -q "$svc" 2>/dev/null || true)"
  [[ -n "$cid" ]] || return 0
  docker inspect -f '{{index .Image}}' "$cid" 2>/dev/null | head -n1 || true
}

write_last_good() {
  local sha="$1"
  local tmp="${LAST_GOOD_FILE}.tmp"
  local main_img tech_img vel_img auth_img
  main_img="$(image_digest main)"
  tech_img="$(image_digest tech)"
  vel_img="$(image_digest velocity)"
  auth_img="$(image_digest auth)"
  if command -v python3 >/dev/null 2>&1; then
    LAST_GOOD_SHA="$sha" \
    MAIN_IMG="$main_img" TECH_IMG="$tech_img" VEL_IMG="$vel_img" AUTH_IMG="$auth_img" \
    python3 - <<'PY' >"$tmp"
import json, os, sys
from datetime import datetime, timezone
doc = {
  "sha": os.environ["LAST_GOOD_SHA"],
  "savedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
  "images": {
    "velocity": os.environ.get("VEL_IMG") or None,
    "main": os.environ.get("MAIN_IMG") or None,
    "tech": os.environ.get("TECH_IMG") or None,
    "auth": os.environ.get("AUTH_IMG") or None,
  },
}
json.dump(doc, sys.stdout, indent=2)
sys.stdout.write("\n")
PY
  else
    printf '{\n  "sha": "%s",\n  "savedAt": "%s",\n  "images": {\n    "velocity": "%s",\n    "main": "%s",\n    "tech": "%s",\n    "auth": "%s"\n  }\n}\n' \
      "$sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$vel_img" "$main_img" "$tech_img" "$auth_img" >"$tmp"
  fi
  mv -f "$tmp" "$LAST_GOOD_FILE"
  echo "wrote last-good $LAST_GOOD_FILE (sha=$sha)"
}

compose_pull_up() {
  local tag="$1"
  export IMAGE_TAG="$tag"
  echo "IMAGE_TAG=$IMAGE_TAG"
  echo "docker compose -f $COMPOSE_FILE pull $SERVICES"
  docker compose -f "$COMPOSE_FILE" pull $SERVICES || docker compose -f "$COMPOSE_FILE" pull
  echo "docker compose -f $COMPOSE_FILE up -d --remove-orphans $SERVICES"
  docker compose -f "$COMPOSE_FILE" up -d --remove-orphans $SERVICES
}

git_pin() {
  local sha="$1"
  echo "git fetch + hard reset $sha"
  git config --global --add safe.directory "$ROOT"
  export GIT_SSH_COMMAND="${GIT_SSH_COMMAND:-ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new}"
  git fetch --depth=1 origin "$sha"
  git reset --hard FETCH_HEAD
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

if [[ ! -x "$HEALTH_SCRIPT" ]]; then
  chmod +x "$HEALTH_SCRIPT" || true
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

if [[ -z "$DEPLOY_SHA" ]]; then
  DEPLOY_SHA="$(git rev-parse HEAD)"
  echo "DEPLOY_SHA unset — using current HEAD $DEPLOY_SHA"
fi

if [[ -d .git ]]; then
  git_pin "$DEPLOY_SHA"
fi

# Pin images to the commit tag images.yml pushes alongside :dev.
compose_pull_up "$DEPLOY_SHA"

health_ok=0
if "$HEALTH_SCRIPT" "$COMPOSE_FILE" "$HEALTH_TIMEOUT_S"; then
  health_ok=1
else
  echo "post-deploy health FAILED for $DEPLOY_SHA" >&2
fi

if [[ "$health_ok" -eq 1 ]]; then
  write_last_good "$DEPLOY_SHA"
  echo "deploy finished — clearing maintenance"
  clear_maintenance
  trap - EXIT
  echo "done"
  docker compose -f "$COMPOSE_FILE" ps
  exit 0
fi

# --- rollback ---
last_sha="$(read_last_good_sha || true)"
if [[ -z "$last_sha" ]]; then
  alert "Deploy $DEPLOY_SHA failed health check and no .deploy-last-good.json — leaving maintenance ON"
  # Keep maintenance so players do not join a broken stack.
  trap - EXIT
  exit 1
fi

if [[ "$last_sha" == "$DEPLOY_SHA" ]]; then
  alert "Deploy $DEPLOY_SHA failed health check; last-good is the same sha — cannot rollback, maintenance ON"
  trap - EXIT
  exit 1
fi

alert "Deploy $DEPLOY_SHA failed health — rolling back to last-good $last_sha"
if [[ -d .git ]]; then
  git_pin "$last_sha"
fi
compose_pull_up "$last_sha"

if "$HEALTH_SCRIPT" "$COMPOSE_FILE" "$HEALTH_TIMEOUT_S"; then
  alert "Rollback to $last_sha succeeded after failed deploy $DEPLOY_SHA"
  echo "rollback healthy — clearing maintenance"
  clear_maintenance
  trap - EXIT
  docker compose -f "$COMPOSE_FILE" ps
  # Non-zero so Actions marks the deploy red even though the stack recovered.
  exit 1
fi

alert "Rollback to $last_sha ALSO failed health after bad deploy $DEPLOY_SHA — maintenance left ON"
trap - EXIT
exit 1
