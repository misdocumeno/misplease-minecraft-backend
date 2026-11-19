#!/usr/bin/env python3
"""Hibernate an idle backend: freeze it and push its pages out of RAM.

Runs as root in its own container (see the `hibernate` service in
docker-compose.prod.yml): it needs `docker.sock` to pause/unpause and
`/sys/fs/cgroup` mounted writable for `memory.reclaim`, which root-only.

Lifecycle of a managed server:

    awake --(empty for HIBERNATE_AFTER_SECONDS)--> save-all flush
          --> docker pause --> memory.reclaim in steps --> publish "asleep"
    asleep --(wake key set by velocity-wake)--> docker unpause
          --> wait for RCON --> publish "awake"

State lives in Redis so the proxy and this agent never talk to each other
directly:

    misplease:hibernate:state          JSON {"main": "asleep", "tech": "awake"}
    misplease:hibernate:wake:<server>  set by velocity-wake, deleted on thaw

Measured on prod (main, 3.4 GiB, 2026-11-19): frozen 122 MiB, host available
+2.4 GiB, thaw 1.2 s. Reclaim is the slow part (~45 s for 3 GiB), so it is
chunked and can be interrupted by a wake request.

Refusals (log and stay awake): no presence data or stale presence, a deploy
pending (`wanted != applied`), maintenance on, a restic backup running, a wake
request pending, HIBERNATE_DRY_RUN=1.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

POLL_SECONDS = float(os.getenv("HIBERNATE_POLL_SECONDS", "15"))
AFTER_SECONDS = float(os.getenv("HIBERNATE_AFTER_SECONDS", "600"))
SERVERS = [s.strip() for s in os.getenv("HIBERNATE_SERVERS", "main,tech").split(",") if s.strip()]
DRY_RUN = os.getenv("HIBERNATE_DRY_RUN", "0") == "1"
RECLAIM_STEP_MB = int(os.getenv("HIBERNATE_RECLAIM_STEP_MB", "512"))
PRESENCE_MAX_AGE = float(os.getenv("HIBERNATE_PRESENCE_MAX_AGE", "30"))

STATE_KEY = "misplease:hibernate:state"
WAKE_PREFIX = "misplease:hibernate:wake:"
PRESENCE_KEY = "misplease:presence:proxy"
MAINTENANCE_KEY = "misplease:maintenance"

MISPLEASE_ROOT = Path(os.getenv("MISPLEASE_ROOT", "/home/mis/misplease"))
RCON_PASSWORD = os.getenv("RCON_PASSWORD", "")
RCON_PORT = int(os.getenv("RCON_PORT", "25575"))
CGROUP_ROOT = Path(os.getenv("HIBERNATE_CGROUP_ROOT", "/sys/fs/cgroup"))

# server -> unix time it first looked empty (reset whenever it is not)
empty_since: dict[str, float] = {}
# server -> state we last published
published: dict[str, str] = {}


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def redis(*args: str) -> str:
    """Thin redis-cli wrapper; the image already ships it (same as the deployer)."""
    out = subprocess.run(
        ["redis-cli", "-u", os.environ["REDIS_URL"], "--no-auth-warning", *args],
        capture_output=True, text=True, timeout=15,
    )
    if out.returncode != 0:
        raise RuntimeError(f"redis-cli {args[0]} failed: {out.stderr.strip()}")
    return out.stdout.strip()


def read_json(key: str) -> dict | None:
    raw = redis("GET", key)
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def publish_state(state: dict[str, str]) -> None:
    redis("SET", STATE_KEY, json.dumps(state, sort_keys=True))


def set_state(server: str, state: str) -> None:
    published[server] = state
    publish_state(published)


def docker(*args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)


def container(name: str) -> str:
    return f"misplease-{name}"


def cgroup_dir(name: str) -> Path:
    cid = docker("inspect", "-f", "{{.Id}}", container(name)).stdout.strip()
    if not cid:
        raise RuntimeError(f"cannot resolve container id for {name}")
    return CGROUP_ROOT / "system.slice" / f"docker-{cid}.scope"


def rcon(server: str, command: str, timeout: int = 20) -> str:
    from mcrcon import MCRcon

    with MCRcon(server, RCON_PASSWORD, port=RCON_PORT, timeout=timeout) as mcr:
        return mcr.command(command)


def rcon_ready(server: str) -> bool:
    try:
        rcon(server, "list", timeout=3)
        return True
    except Exception:
        return False


def empty_servers(presence: dict | None) -> set[str] | None:
    """Names that currently have no players, or None if the data is unusable."""
    if not presence:
        return None
    try:
        updated = presence.get("updatedAt")
        if updated:
            # ISO-8601 with Z; only the age matters.
            from datetime import datetime, timezone

            then = datetime.fromisoformat(updated.replace("Z", "+00:00"))
            age = (datetime.now(timezone.utc) - then).total_seconds()
            if age > PRESENCE_MAX_AGE:
                return None
    except Exception:
        return None
    occupied = presence.get("servers") or {}
    return {name for name in SERVERS if int(occupied.get(name, 0)) == 0}


def deploy_pending() -> bool:
    state_file = MISPLEASE_ROOT / ".deploy-state.json"
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except Exception:
        return False
    wanted, applied = state.get("wanted"), state.get("applied")
    return bool(wanted) and wanted != applied


def maintenance_on() -> bool:
    value = redis("GET", MAINTENANCE_KEY)
    return value in ("1", "true", "on", "ON")


def backup_running() -> bool:
    out = docker("ps", "--filter", "name=misplease-backup", "--filter", "status=running", "-q")
    return bool(out.stdout.strip())


def wake_requested(server: str) -> bool:
    return bool(redis("GET", WAKE_PREFIX + server))


def clear_wake(server: str) -> None:
    redis("DEL", WAKE_PREFIX + server)


def is_paused(server: str) -> bool:
    out = docker("inspect", "-f", "{{.State.Paused}}", container(server)).stdout.strip()
    return out == "true"


def reconcile() -> None:
    """Trust Docker over Redis: the agent may have restarted while a server slept."""
    state = {}
    for server in SERVERS:
        try:
            state[server] = "asleep" if is_paused(server) else "awake"
        except Exception as e:
            log(f"{server}: cannot read paused state: {e}")
            continue
        published[server] = state[server]
    if state:
        publish_state(state)
        log(f"reconciled: {state}")


def freeze(server: str) -> None:
    name = container(server)
    log(f"{server}: freezing")

    if not rcon_ready(server):
        log(f"{server}: RCON not answering, not freezing")
        return

    saved = rcon(server, "save-all flush")
    log(f"{server}: {saved.strip()[:80]}")
    if "Saved the game" not in saved and "Saving" not in saved:
        log(f"{server}: save not confirmed, not freezing")
        return

    res = docker("pause", name)
    if res.returncode != 0:
        log(f"{server}: pause failed: {res.stderr.strip()}")
        return
    set_state(server, "asleep")

    cg = cgroup_dir(server)
    total = 0
    try:
        while True:
            if wake_requested(server):
                log(f"{server}: wake requested during reclaim, stopping reclaim")
                break
            before = int((cg / "memory.current").read_text().strip())
            if before < RECLAIM_STEP_MB * 1024 * 1024:
                break
            try:
                (cg / "memory.reclaim").write_text(str(RECLAIM_STEP_MB * 1024 * 1024))
            except OSError as e:
                log(f"{server}: reclaim stopped: {e}")
                break
            total += RECLAIM_STEP_MB
            log(f"{server}: reclaimed {total} MiB (current "
                f"{int((cg / 'memory.current').read_text().strip()) // (1024 * 1024)} MiB)")
    finally:
        current = int((cg / "memory.current").read_text().strip()) // (1024 * 1024)
        log(f"{server}: asleep, memory.current {current} MiB")


def thaw(server: str) -> None:
    name = container(server)
    log(f"{server}: wake requested, thawing")
    res = docker("unpause", name)
    if res.returncode != 0:
        log(f"{server}: unpause failed: {res.stderr.strip()}")
        return
    set_state(server, "awake")
    started = time.monotonic()
    while time.monotonic() - started < 60:
        if rcon_ready(server):
            log(f"{server}: awake after {time.monotonic() - started:.1f}s")
            break
        time.sleep(0.5)
    else:
        log(f"{server}: unpaused but RCON still not answering after 60s")
    clear_wake(server)


def tick() -> None:
    presence = read_json(PRESENCE_KEY)
    empty = empty_servers(presence)
    state = read_json(STATE_KEY) or {}

    for server in SERVERS:
        current = state.get(server, "awake")
        published[server] = current

    for server in SERVERS:
        if published.get(server) != "asleep":
            continue
        # Something outside this agent may have thawed it (a deploy unpauses
        # every backend before `compose up`); trust Docker over our own state.
        try:
            if not is_paused(server):
                log(f"{server}: no longer paused, publishing awake")
                set_state(server, "awake")
                continue
        except Exception as e:
            log(f"{server}: cannot read paused state: {e}")
            continue
        if wake_requested(server):
            thaw(server)

        if empty is None:
            empty_since.pop(server, None)
            continue

        if server not in empty:
            empty_since.pop(server, None)
            if published.get(server) != "awake":
                set_state(server, "awake")
            continue

        since = empty_since.setdefault(server, time.monotonic())
        idle = time.monotonic() - since
        if idle < AFTER_SECONDS:
            continue

        reason = None
        if deploy_pending():
            reason = "deploy pending"
        elif maintenance_on():
            reason = "maintenance on"
        elif backup_running():
            reason = "backup running"
        elif wake_requested(server):
            reason = "wake requested"
        if reason:
            log(f"{server}: empty {idle:.0f}s but {reason}, staying awake")
            continue

        if DRY_RUN:
            log(f"{server}: empty {idle:.0f}s, would freeze (dry run)")
            continue

        freeze(server)


def run() -> None:
    log(f"hibernate agent: servers={SERVERS} after={AFTER_SECONDS:.0f}s "
        f"step={RECLAIM_STEP_MB} MiB dry_run={DRY_RUN}")
    if not RCON_PASSWORD:
        log("RCON_PASSWORD is empty: freezing is disabled")
    while True:
        try:
            reconcile()
            break
        except Exception as e:
            log(f"reconcile failed: {type(e).__name__}: {e}")
            time.sleep(POLL_SECONDS)
    while True:
        try:
            tick()
        except Exception as e:
            log(f"tick failed: {type(e).__name__}: {e}")
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "once":
        tick()
    else:
        run()
