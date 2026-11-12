#!/usr/bin/env bash
# Wait for game containers to finish boot and fail on blocking enable/load errors.
# Intentionally ignores third-party WARN noise (Slimefun biomes, Essentials "unsupported", etc.).
#
# Usage: post-deploy-health.sh <compose-file> [timeout-seconds]
# Must run with cwd = stack root (compose project).
set -euo pipefail

COMPOSE_FILE="${1:?compose file}"
TIMEOUT_S="${2:-180}"
CONTAINERS=(misplease-proxy misplease-main misplease-tech misplease-auth)
BOOT_CONTAINERS=(misplease-proxy misplease-main misplease-tech)

# Blocking only — same family as docker/smoke-check.sh FAIL_RE, plus overlay/TAB fatals.
FAIL_RE='Error occurred while enabling |Could not load |UnknownDependencyException|Fatal IO error occurred while enabling|AccessDeniedException: plugins/|\[SmokeCheck\] FAIL|\[DirectoryProviderSource\]|\[ModernPluginLoadingStrategy\]|Directory .*\.jar. does not contain a (paper-)?plugin\.yml'

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

has_done() {
  docker logs "$1" 2>&1 | grep -q 'Done ('
}

echo "post-deploy health: waiting up to ${TIMEOUT_S}s for Done ( on proxy/main/tech + auth Up"

deadline=$((SECONDS + TIMEOUT_S))
proxy_done=0
main_done=0
tech_done=0

while (( SECONDS < deadline )); do
  all_up=1
  for c in "${CONTAINERS[@]}"; do
    if ! docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -qx true; then
      all_up=0
      break
    fi
  done
  if [[ "$all_up" -ne 1 ]]; then
    sleep 3
    continue
  fi

  if [[ "$proxy_done" -eq 0 ]] && has_done misplease-proxy; then
    proxy_done=1
    echo "  misplease-proxy: Done"
  fi
  if [[ "$main_done" -eq 0 ]] && has_done misplease-main; then
    main_done=1
    echo "  misplease-main: Done"
  fi
  if [[ "$tech_done" -eq 0 ]] && has_done misplease-tech; then
    tech_done=1
    echo "  misplease-tech: Done"
  fi

  if [[ "$proxy_done" -eq 1 && "$main_done" -eq 1 && "$tech_done" -eq 1 ]]; then
    break
  fi
  sleep 3
done

failed=0
for c in "${CONTAINERS[@]}"; do
  if ! docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -qx true; then
    echo "health FAIL: $c is not running" >&2
    docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' "$c" 2>&1 || true
    failed=1
  fi
done

[[ "$proxy_done" -eq 1 ]] || { echo "health FAIL: misplease-proxy never logged Done ( within ${TIMEOUT_S}s" >&2; docker logs --tail 80 misplease-proxy 2>&1 || true; failed=1; }
[[ "$main_done" -eq 1 ]] || { echo "health FAIL: misplease-main never logged Done ( within ${TIMEOUT_S}s" >&2; docker logs --tail 80 misplease-main 2>&1 || true; failed=1; }
[[ "$tech_done" -eq 1 ]] || { echo "health FAIL: misplease-tech never logged Done ( within ${TIMEOUT_S}s" >&2; docker logs --tail 80 misplease-tech 2>&1 || true; failed=1; }

for c in "${BOOT_CONTAINERS[@]}"; do
  log="$(mktemp)"
  docker logs "$c" >"$log" 2>&1 || true
  if grep -E "$FAIL_RE" "$log" >/tmp/misplease-health-hits.txt; then
    echo "health FAIL: blocking log lines in $c" >&2
    cat /tmp/misplease-health-hits.txt >&2
    failed=1
  fi
  rm -f "$log"
done

if [[ "$failed" -ne 0 ]]; then
  echo "---- compose ps ----" >&2
  compose ps >&2 || true
  exit 1
fi

echo "post-deploy health OK"
compose ps
