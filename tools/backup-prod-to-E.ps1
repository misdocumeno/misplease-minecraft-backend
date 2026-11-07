# Resumable prod backup → E:\ 
# 1) stop main/tech/velocity
# 2) pg_dump/redis/meta + local rsync staging on the VM
# 3) docker rsync pull of the staging tree (resumable)
# 4) compose start (no recreate)
#
#   .\tools\backup-prod-to-E.ps1 -DestDir "E:\backups\misplease\20261105-sleep"

param(
  [string]$DestRoot = "E:\backups\misplease",
  [string]$HostName = "192.168.100.200",
  [string]$User = "mis",
  [string]$RemoteRoot = "/home/mis/misplease",
  [string]$RemoteStage = "/home/mis/backups/misplease-20261105-sleep",
  [string]$Identity = ".cursor/ssh/misplease_vm",
  [string]$KnownHosts = ".cursor/ssh/known_hosts",
  [string]$DestDir = "",
  [switch]$SkipStop,
  [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$utf8 = New-Object System.Text.UTF8Encoding $false

if (-not (Test-Path "E:\")) { throw "E: drive not available" }
if ([string]::IsNullOrWhiteSpace($DestDir)) {
  $DestDir = Join-Path $DestRoot (Get-Date -Format "yyyyMMdd-HHmmss")
}
New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

$sshBase = @(
  "-i", $Identity,
  "-o", "UserKnownHostsFile=$KnownHosts",
  "-o", "StrictHostKeyChecking=accept-new",
  "${User}@${HostName}"
)

function Write-UnixFile([string]$Path, [string]$Content) {
  $clean = $Content.TrimStart([char]0xFEFF) -replace "`r`n", "`n" -replace "`r", "`n"
  [System.IO.File]::WriteAllText($Path, $clean, $utf8)
}

function Invoke-RemoteScriptFile([string]$LocalScriptPath) {
  $remote = "/tmp/misplease-agent-remote.sh"
  & scp @("-i", $Identity, "-o", "UserKnownHostsFile=$KnownHosts", "-o", "StrictHostKeyChecking=accept-new") `
    $LocalScriptPath "${User}@${HostName}:$remote"
  if ($LASTEXITCODE -ne 0) { throw "scp failed ($LASTEXITCODE)" }
  & ssh @sshBase "tr -d '\r' < $remote > ${remote}.lf && mv ${remote}.lf $remote && bash $remote"
  if ($LASTEXITCODE -ne 0) { throw "remote script failed ($LASTEXITCODE)" }
}

Write-Host "backup dest: $DestDir"
Write-Host "remote stage: $RemoteStage"
$stopped = $false

try {
  if (-not $SkipStop) {
    Write-Host "stopping main/tech/velocity..."
    $p = Join-Path $env:TEMP "misplease-stop.sh"
    Write-UnixFile $p @"
#!/usr/bin/env bash
set -euo pipefail
cd '$RemoteRoot'
docker compose stop main tech velocity
"@
    Invoke-RemoteScriptFile $p
    $stopped = $true
  }

  Write-Host "staging backup on VM (local rsync, resumable)..."
  $stage = Join-Path $env:TEMP "misplease-stage.sh"
  Write-UnixFile $stage @"
#!/usr/bin/env bash
set -euo pipefail
ROOT='$RemoteRoot'
STAGE='$RemoteStage'
EXTRA="`$STAGE/_extra"
TREE="`$STAGE/tree"
mkdir -p "`$EXTRA" "`$TREE"

docker exec misplease-db pg_dump -U misplease -Fc misplease > "`$EXTRA/misplease.dump"
docker exec misplease-redis redis-cli SAVE >/dev/null || true
docker cp misplease-redis:/data/dump.rdb "`$EXTRA/redis.rdb" 2>/dev/null || true
{
  echo '=== date ==='
  date -Is
  echo '=== git ==='
  git -C "`$ROOT" rev-parse HEAD 2>/dev/null || true
  git -C "`$ROOT" status -sb 2>/dev/null || true
  echo '=== compose ==='
  docker compose -f "`$ROOT/docker-compose.yml" ps -a
  echo '=== disk ==='
  df -h
} > "`$EXTRA/meta.txt"

rsync -a --delete \
  --exclude='.git/' \
  --exclude='.plugin-lock-cache/' \
  --exclude='node_modules/' \
  --exclude='**/node_modules/' \
  --exclude='**/.gradle/' \
  --exclude='**/build/' \
  --exclude='**/dist/' \
  --exclude='**/*.log' \
  --exclude='**/logs/' \
  --exclude='**/cache/' \
  --exclude='**/tmp/' \
  --exclude='**/error-reports/' \
  --exclude='**/spark/tmp/' \
  --exclude='**/dynmap/web/tiles/' \
  --exclude='**/dynmap/tiles/' \
  --exclude='**/AsyncWorldEdit/undo/' \
  --exclude='**/.archive-unpack/' \
  --exclude='**/bStats/' \
  --exclude='**/crash-reports/' \
  "`$ROOT"/ "`$TREE"/

du -sh "`$STAGE" "`$TREE" "`$EXTRA"
"@
  Invoke-RemoteScriptFile $stage

  $idAbs = (Resolve-Path $Identity).Path
  $khAbs = (Resolve-Path $KnownHosts).Path
  $destAbs = (Resolve-Path $DestDir).Path

  Write-Host "pulling stage with docker rsync (resumable)..."
  $pull = Join-Path $env:TEMP "misplease-pull.sh"
  # Literal sh script — no PowerShell expansion inside single-quote here-string.
  $pullBody = @'
#!/bin/sh
set -eu
cp /ssh/misplease_vm /tmp/misplease_vm
cp /ssh/known_hosts /tmp/known_hosts
chmod 600 /tmp/misplease_vm
export RSYNC_RSH="ssh -i /tmp/misplease_vm -o UserKnownHostsFile=/tmp/known_hosts -o StrictHostKeyChecking=accept-new"
rsync -a --partial --info=progress2 USER@HOST:REMOTE_STAGE/ /dest/
'@
  $pullBody = $pullBody.Replace("USER", $User).Replace("HOST", $HostName).Replace("REMOTE_STAGE", $RemoteStage)
  Write-UnixFile $pull $pullBody

  & docker run --rm `
    -v "${idAbs}:/ssh/misplease_vm:ro" `
    -v "${khAbs}:/ssh/known_hosts:ro" `
    -v "${destAbs}:/dest" `
    -v "${pull}:/pull.sh:ro" `
    instrumentisto/rsync-ssh:alpine3.23 `
    sh -c "tr -d '\r' < /pull.sh > /tmp/pull.sh && sh /tmp/pull.sh"
  if ($LASTEXITCODE -ne 0) { throw "rsync pull failed ($LASTEXITCODE)" }

  $size = (Get-ChildItem $DestDir -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
  Write-Host ("backup tree size: {0:N1} GiB" -f ($size / 1GB))
  Write-Host "done: $DestDir"
}
finally {
  if ($stopped -and -not $SkipStart) {
    Write-Host "starting main/tech/velocity (compose start)..."
    try {
      $p = Join-Path $env:TEMP "misplease-start.sh"
      Write-UnixFile $p @"
#!/usr/bin/env bash
set -euo pipefail
cd '$RemoteRoot'
docker compose start main tech velocity
docker compose ps
"@
      Invoke-RemoteScriptFile $p
    } catch {
      Write-Host "WARN: restart failed: $_"
    }
  }
}
