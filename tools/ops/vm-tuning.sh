#!/usr/bin/env bash
# Host-level memory tuning for the prod VM. Idempotent; run with sudo:
#
#   sudo bash tools/ops/vm-tuning.sh
#
# Runtime values apply immediately; the same script writes the persistence files
# so a reboot keeps them. Reasoning and the numbers to watch:
# .local/notes/ram-plan.md.
#
# KSM is deliberately NOT enabled: it only merges VMAs a process marked
# MADV_MERGEABLE, and the JVM never does. Measured on prod: ksmd runs,
# pages_scanned stays 0 forever. It would look like a win and save nothing.
set -euo pipefail

SWAPPINESS="${SWAPPINESS:-10}"
ZSWAP_POOL_PERCENT="${ZSWAP_POOL_PERCENT:-20}"

log() { printf '==> %s\n' "$*"; }

# --- 1. swappiness -----------------------------------------------------------
# The kernel was swapping hot JVM pages while keeping 1 GB of page cache
# (swappiness 60). Lowering it to 10 faulted ~1 GB back in, swap went from
# 2.8 GB to 0.5 GB and memory pressure fell to 0.
log "vm.swappiness=${SWAPPINESS}"
sysctl -q -w "vm.swappiness=${SWAPPINESS}"
cat > /etc/sysctl.d/90-misplease.conf <<EOF
# Managed by tools/ops/vm-tuning.sh (misplease). Do not edit by hand.
vm.swappiness = ${SWAPPINESS}
EOF
sysctl -q --system

# --- 2. zswap ----------------------------------------------------------------
# Compressed swap cache in front of the swap files. Also the mechanism the
# freeze-an-empty-server idea (see ram-plan) relies on.
if [ -d /sys/module/zswap/parameters ]; then
  for c in zstd lz4 lzo; do
    if echo "$c" > /sys/module/zswap/parameters/compressor 2>/dev/null; then
      log "zswap compressor=${c}"
      break
    fi
  done
  echo "$ZSWAP_POOL_PERCENT" > /sys/module/zswap/parameters/max_pool_percent
  echo 1 > /sys/module/zswap/parameters/enabled
  log "zswap enabled, max_pool_percent=${ZSWAP_POOL_PERCENT}"

  # Persist for the next boot (kernel cmdline, not a module parameter).
  if ! grep -q 'zswap.enabled=1' /etc/default/grub; then
    comp="$(cat /sys/module/zswap/parameters/compressor)"
    sed -i "s|^GRUB_CMDLINE_LINUX_DEFAULT=\"\(.*\)\"|GRUB_CMDLINE_LINUX_DEFAULT=\"\1 zswap.enabled=1 zswap.compressor=${comp} zswap.max_pool_percent=${ZSWAP_POOL_PERCENT}\"|" /etc/default/grub
    update-grub >/dev/null
    log "GRUB cmdline updated (takes effect on next reboot)"
  fi
else
  log "zswap not available on this kernel"
fi

# --- report ------------------------------------------------------------------
echo
log "current state"
printf 'swappiness            : %s\n' "$(cat /proc/sys/vm/swappiness)"
printf 'zswap enabled         : %s\n' "$(cat /sys/module/zswap/parameters/enabled 2>/dev/null || echo n/a)"
printf 'zswap compressor      : %s\n' "$(cat /sys/module/zswap/parameters/compressor 2>/dev/null || echo n/a)"
printf 'zswap max_pool_percent: %s\n' "$(cat /sys/module/zswap/parameters/max_pool_percent 2>/dev/null || echo n/a)"
free -h
echo
echo "Watch pressure (should stay at 0.00): cat /proc/pressure/memory"
echo "Watch zswap use: grep -E 'zswap|Zswap' /proc/meminfo"
