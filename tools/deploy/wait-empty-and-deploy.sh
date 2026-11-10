#!/usr/bin/env bash
# Wait until velocity-presence reports proxy empty for EMPTY_SECONDS, then
# ff-only update the stack checkout (optional) and docker compose pull + up.
# Intended for a self-hosted Actions runner on the host.
#
# Why presence.json (not HTTP): Redis is the network SoT; the JSON is the
# host-local mirror written by velocity-presence on the same VM as the runner.
# File read is direct, no extra service/port. Use an HTTP gate only if a remote
# checker appears later.
set -euo pipefail

ROOT="${MISPLEASE_ROOT:-/home/mis/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
PRESENCE_FILE="${PRESENCE_FILE:-$ROOT/infra/velocity/data/presence.json}"
EMPTY_SECONDS="${EMPTY_SECONDS:-60}"
POLL_SECONDS="${POLL_SECONDS:-5}"
# 0 = wait forever (pending until empty).
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-0}"
# Optional: commit SHA that images built (workflow_run head_sha).
DEPLOY_SHA="${DEPLOY_SHA:-}"

cd "$ROOT"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "missing compose file: $ROOT/$COMPOSE_FILE" >&2
  exit 1
fi

empty_since=""
started="$(date +%s)"

is_empty() {
  if [[ ! -f "$PRESENCE_FILE" ]]; then
    echo "presence file missing: $PRESENCE_FILE (is velocity-presence running?)" >&2
    return 1
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$PRESENCE_FILE" <<'PY'
import json, sys
p = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if p.get("empty") is True and int(p.get("count") or 0) == 0 else 1)
PY
  elif command -v node >/dev/null 2>&1; then
    node -e 'const p=require(process.argv[1]); process.exit(p.empty===true && (p.count|0)===0?0:1)' "$PRESENCE_FILE"
  else
    echo "need python3 or node to parse presence.json" >&2
    exit 1
  fi
}

echo "waiting for proxy empty ≥ ${EMPTY_SECONDS}s (presence=$PRESENCE_FILE)"

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
