#!/bin/sh
# Hot backups while the game stack runs (not tied to restarter / empty gate).
# Mounted into the `backup` service as /scripts/restic-loop.sh
#   path in repo: tools/backup/restic-loop.sh
#
# Runs once per day at BACKUP_AT_HOUR:BACKUP_AT_MINUTE (UTC),
# not "N hours after container start".
set -eu

AT_HOUR="${BACKUP_AT_HOUR:-6}"
AT_MINUTE="${BACKUP_AT_MINUTE:-0}"
WORKDIR=/tmp/misplease-backup
ROOT="${MISPLEASE_ROOT:-/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
mkdir -p "$WORKDIR"

# Seconds until next wall-clock HH:MM in UTC. Pure arithmetic (BusyBox/Alpine safe).
seconds_until_next_run() {
  hour="$1"
  minute="$2"
  # Force decimal even when HH/MM/SS have leading zeros (ash treats 08 as octal).
  H=$((1$(date -u +%H) - 100))
  M=$((1$(date -u +%M) - 100))
  S=$((1$(date -u +%S) - 100))
  now_s=$((H * 3600 + M * 60 + S))
  target_s=$((hour * 3600 + minute * 60))
  delta=$((target_s - now_s))
  if [ "$delta" -le 0 ]; then
    delta=$((delta + 86400))
  fi
  echo "$delta"
}

echo "restic backup daily at $(printf '%02d:%02d' "$AT_HOUR" "$AT_MINUTE") UTC → $RESTIC_REPOSITORY"

restic snapshots >/dev/null 2>&1 || restic init

while true; do
  wait_s="$(seconds_until_next_run "$AT_HOUR" "$AT_MINUTE")"
  echo "[$(date -u +%Y%m%dT%H%M%SZ)] sleeping ${wait_s}s until next backup"
  sleep "$wait_s"

  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  dump="$WORKDIR/misplease-$stamp.sql"
  echo "[$stamp] pg_dump"
  if command -v docker >/dev/null 2>&1 && [ -S /var/run/docker.sock ]; then
    docker compose -f "$ROOT/$COMPOSE_FILE" exec -T db \
      pg_dump -U misplease --no-owner misplease >"$dump" || echo "pg_dump failed" >&2
  else
    echo "docker.sock unavailable — skipping SQL dump" >&2
  fi

  # First-ever snapshot also gets tag `genesis` (kept forever via --keep-tag).
  # Automatic: any run with no existing genesis-tagged snapshot gets --tag genesis.
  extra_tag=""
  if ! restic snapshots --tag genesis --json 2>/dev/null | grep -q '"id"'; then
    extra_tag="--tag genesis"
    echo "[$stamp] tagging this snapshot as genesis"
  fi

  echo "[$stamp] restic backup"
  if [ -f "$dump" ]; then
    # shellcheck disable=SC2086
    restic backup --tag misplease --tag "at=$stamp" $extra_tag /data/infra "$dump" \
      || echo "restic backup failed" >&2
  else
    # shellcheck disable=SC2086
    restic backup --tag misplease --tag "at=$stamp" $extra_tag /data/infra \
      || echo "restic backup failed" >&2
  fi
  rm -f "$dump" || true

  echo "[$stamp] forget/prune (daily×7, weekly×4, monthly×6, keep-tag genesis)"
  restic forget --prune \
    --keep-daily 7 \
    --keep-weekly 4 \
    --keep-monthly 6 \
    --keep-tag genesis \
    --tag misplease || true
done
