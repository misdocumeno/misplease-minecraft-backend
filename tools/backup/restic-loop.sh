#!/bin/sh
# Periodic restic snapshot: pg_dump via compose db + infra bind (worlds under infra/*/data).
# Repository lives outside the game stack (rest-server on the Windows host).
set -eu

INTERVAL_H="${BACKUP_INTERVAL_HOURS:-24}"
INTERVAL_S=$((INTERVAL_H * 3600))
WORKDIR=/tmp/misplease-backup
ROOT="${MISPLEASE_ROOT:-/misplease}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
mkdir -p "$WORKDIR"

echo "restic backup loop every ${INTERVAL_H}h → $RESTIC_REPOSITORY"

restic snapshots >/dev/null 2>&1 || restic init

while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  dump="$WORKDIR/misplease-$stamp.sql"
  echo "[$stamp] pg_dump"
  if command -v docker >/dev/null 2>&1 && [ -S /var/run/docker.sock ]; then
    docker compose -f "$ROOT/$COMPOSE_FILE" exec -T db \
      pg_dump -U misplease --no-owner misplease >"$dump" || echo "pg_dump failed" >&2
  else
    echo "docker.sock unavailable — skipping SQL dump" >&2
  fi

  echo "[$stamp] restic backup"
  if [ -f "$dump" ]; then
    restic backup --tag misplease --tag "at=$stamp" /data/infra "$dump" || echo "restic backup failed" >&2
  else
    restic backup --tag misplease --tag "at=$stamp" /data/infra || echo "restic backup failed" >&2
  fi
  rm -f "$dump" || true

  echo "[$stamp] forget/prune"
  restic forget --prune \
    --keep-daily 7 \
    --keep-weekly 4 \
    --keep-monthly 6 \
    --tag misplease || true

  echo "[$stamp] sleeping ${INTERVAL_H}h"
  sleep "$INTERVAL_S"
done
