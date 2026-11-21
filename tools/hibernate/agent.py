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

While anything sleeps the loop watches those keys once a second, because a
player's connect is waiting inside Velocity's `connection-timeout` (10 s).

Measured on prod (main, 3.4 GiB, 2026-11-19): frozen 122 MiB, host available
+2.4 GiB, thaw 1.2 s. Reclaim is the slow part (~45 s for 3 GiB), so it is
chunked and can be interrupted by a wake request.

Refusals (log and stay awake): no presence data, a deploy pending
(`wanted != applied`), maintenance on, a restic backup running, a wake request
pending, a missing RCON password, HIBERNATE_DRY_RUN=1.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# Pure decisions live in decisions.py so they can be tested without root/docker.
# The script's directory is on sys.path both for `/app/agent.py` in the container
# and for `python tools/hibernate/agent.py` from the repo root.
from decisions import (  # noqa: E402
    freeze_blocker,
    presence_age_seconds,
    redis_endpoint,
    should_reclaim,
    tick_due,
)

POLL_SECONDS = float(os.getenv("HIBERNATE_POLL_SECONDS", "15"))
# While something sleeps, watch the wake keys this often instead of POLL_SECONDS.
WAKE_POLL_SECONDS = float(os.getenv("HIBERNATE_WAKE_POLL_SECONDS", "1"))
AFTER_SECONDS = float(os.getenv("HIBERNATE_AFTER_SECONDS", "600"))
SERVERS = [s.strip() for s in os.getenv("HIBERNATE_SERVERS", "main,tech").split(",") if s.strip()]
# Default to logging only: arming is an explicit `HIBERNATE_DRY_RUN=0`.
DRY_RUN = os.getenv("HIBERNATE_DRY_RUN", "1") == "1"
RECLAIM_STEP_MB = int(os.getenv("HIBERNATE_RECLAIM_STEP_MB", "512"))
# Stop reclaiming here: below this the JVM keeps native/direct pages that cannot
# be reclaimed anyway, and every extra MiB is swap-file IO on the way back in.
RECLAIM_FLOOR_MB = int(os.getenv("HIBERNATE_RECLAIM_FLOOR_MB", "192"))
# Liveness probe (replaces Spigot's watchdog, which cannot tell a freeze from a
# hang: its timeout is wall-clock, so every long freeze looked like a hang).
PROBE_SECONDS = float(os.getenv("HIBERNATE_PROBE_SECONDS", "30"))
HANG_ALERT_AFTER = int(os.getenv("HIBERNATE_HANG_ALERT_AFTER", "3"))
HANG_RESTART_AFTER = int(os.getenv("HIBERNATE_HANG_RESTART_AFTER", "6"))
ALERT_WEBHOOK = os.getenv("DEPLOY_ALERT_WEBHOOK", "")
# Consecutive reconcile/tick failures before alerting. One failed call is normal
# while Redis or Docker is being recreated; a stuck loop is not: frozen backends
# cannot wake until it recovers.
ALERT_AFTER_FAILURES = int(os.getenv("HIBERNATE_ALERT_AFTER_FAILURES", "4"))

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
# server -> monotonic time of the last liveness probe
probed_at: dict[str, float] = {}
# server -> consecutive failed probes
hang_failures: dict[str, int] = {}
# server -> we already sent the alert for this run of failures
hang_alerted: dict[str, bool] = {}
# server -> it answered at least one probe since it last started
hang_baseline: dict[str, bool] = {}


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def alert(msg: str) -> None:
    """Same webhook shape as the deployer, so both land in one channel."""
    log(f"ALERT: {msg}")
    if not ALERT_WEBHOOK:
        return
    try:
        import urllib.error
        import urllib.request

        body = json.dumps({"content": msg[:1800]}).encode()
        req = urllib.request.Request(
            ALERT_WEBHOOK,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=15)
    except Exception as e:
        log(f"alert webhook failed: {e}")


def redis(*args: str) -> str:
    """Thin redis-cli wrapper; the image already ships it (same as the deployer).

    The password goes in `REDISCLI_AUTH`, never in argv: the old `-u <url>` form
    leaked credentials into the log whenever a call timed out (Redis restart),
    because `TimeoutExpired` includes the command line.
    """
    host, port, db, password = redis_endpoint(os.environ["REDIS_URL"])
    env = {**os.environ}
    if password:
        env["REDISCLI_AUTH"] = password
    cmd = [
        "redis-cli", "-h", host, "-p", str(port), "-n", db,
        "--no-auth-warning", *args,
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=15, env=env)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"redis-cli {args[0]} timed out after 15s")
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


def probe_liveness(server: str) -> None:
    """Freeze-aware hang detection for a server we expect to be running.

    RCON commands run on the server thread, so `list` never answering is the
    main thread being stuck - exactly what Spigot's watchdog used to catch, but
    this one knows a frozen container is not a hung one (we only probe while the
    container is unpaused, and only after it has answered once).

    Alert first, restart only if it stays stuck. A deploy in flight is exempt:
    `compose up` reboots the backend and RCON is down for a minute or so.
    """
    now = time.monotonic()
    if now - probed_at.get(server, 0.0) < PROBE_SECONDS:
        return
    if deploy_pending():
        return
    probed_at[server] = now
    try:
        rcon(server, "list", timeout=10)
    except Exception as e:
        if not hang_baseline.get(server):
            # Still booting (or this is the first tick after an agent start);
            # wait for the first answer before calling anything a hang.
            return
        failures = hang_failures.get(server, 0) + 1
        hang_failures[server] = failures
        log(f"{server}: liveness probe {failures} failed ({type(e).__name__})")
        if failures >= HANG_ALERT_AFTER and not hang_alerted.get(server):
            hang_alerted[server] = True
            alert(
                f"{server} is not answering RCON ({failures} probes): main thread "
                "looks hung."
            )
        if failures >= HANG_RESTART_AFTER and not DRY_RUN:
            log(f"{server}: still stuck after {failures} probes, restarting container")
            docker("restart", container(server))
            hang_failures[server] = 0
            hang_alerted[server] = False
            hang_baseline[server] = False
            probed_at[server] = time.monotonic() + 120  # let it boot
        return
    if hang_failures.get(server):
        log(f"{server}: liveness probe recovered after {hang_failures[server]} failures")
    hang_failures[server] = 0
    hang_alerted[server] = False
    hang_baseline[server] = True


def presence_age(presence: dict) -> float:
    """Seconds since the proxy last published presence.

    `velocity-presence` writes on boot, join, switch and leave (and re-writes the
    same payload on its heartbeat), so this is how long the current (empty) state
    has been true - not a heartbeat. Same reading as `empty_age_seconds` in
    `tools/deploy/agent.py`; parsing lives in `decisions.py` so it is tested.
    """
    return presence_age_seconds(
        presence.get("updatedAt") or "",
        datetime.now(timezone.utc),
    )


def empty_servers(presence: dict | None) -> set[str] | None:
    """Names that currently have no players, or None if there is no data at all."""
    if not presence:
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
    """True while restic/pg_dump is actually working, not while the loop sleeps.

    `misplease-backup` stays up all day sleeping until the next window, so
    `status=running` on that container says nothing. The real work shows up as a
    child process (`restic backup ...`, `docker compose exec ... pg_dump`).
    """
    out = docker("top", "misplease-backup", "-o", "cmd")
    for line in (out.stdout or "").splitlines():
        if re.search(r"\brestic (init|snapshots|backup|forget|check|unlock|find)\b", line):
            return True
        if "docker compose" in line:
            return True
    return False


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

    # Last gate before the irreversible part: ask the server itself. Presence is
    # published by the proxy on join/leave, so a desynced proxy or a stale Redis
    # value must not be able to freeze a server with people on it.
    listing = rcon(server, "list")
    m = re.search(r"There are (\d+)", listing or "")
    if m is None:
        log(f"{server}: cannot read `list` output ({listing.strip()[:60]!r}), not freezing")
        return
    if int(m.group(1)) != 0:
        log(f"{server}: {m.group(1)} players online, not freezing")
        empty_since.pop(server, None)
        return

    saved = rcon(server, "save-all flush")
    log(f"{server}: {saved.strip()[:80]}")
    if "Saved the game" not in saved and "Saving" not in saved:
        log(f"{server}: save not confirmed, not freezing")
        return
    # `save-all flush` returns once vanilla has written, but region/chunk IO can
    # still be in flight; give it a moment so the freeze never lands on a write.
    time.sleep(3)

    res = docker("pause", name)
    if res.returncode != 0:
        log(f"{server}: pause failed: {res.stderr.strip()}")
        return
    set_state(server, "asleep")
    reclaim(server)


def reclaim(server: str) -> None:
    """Push anon pages out of RAM in steps, stopping at the floor or a wake.

    Root-only (`memory.reclaim`), hence the root container. Steps keep the host
    responsive; the spike on 2026-11-19 measured ~45 s for 3 GiB in one blast.
    """
    try:
        cg = cgroup_dir(server)
    except Exception as e:
        log(f"{server}: cannot resolve cgroup: {e}")
        return
    floor = RECLAIM_FLOOR_MB * 1024 * 1024
    step = RECLAIM_STEP_MB * 1024 * 1024
    done = 0
    while True:
        if wake_requested(server):
            log(f"{server}: wake requested during reclaim, stopping")
            break
        try:
            current = int((cg / "memory.current").read_text().strip())
        except OSError as e:
            log(f"{server}: reclaim stopped: {e}")
            break
        if current <= floor:
            break
        try:
            (cg / "memory.reclaim").write_text(str(step))
        except OSError as e:
            log(f"{server}: reclaim stopped: {e}")
            break
        done += RECLAIM_STEP_MB
        log(f"{server}: reclaimed {done} MiB (current {current // (1024 * 1024)} MiB)")
    try:
        current = int((cg / "memory.current").read_text().strip()) // (1024 * 1024)
        log(f"{server}: asleep, memory.current {current} MiB")
    except OSError:
        pass


def thaw(server: str) -> None:
    name = container(server)
    log(f"{server}: wake requested, thawing")
    res = docker("unpause", name)
    if res.returncode != 0:
        log(f"{server}: unpause failed: {res.stderr.strip()}")
        return
    set_state(server, "awake")
    # Restart the idle clock. Presence only changes when someone joins or leaves,
    # so it still says "empty since hours ago" right after a wake; without this a
    # thawed server freezes again on the next tick, in the middle of the join.
    empty_since[server] = time.monotonic()
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
    # 0 when presence is missing/unparseable; seeding then falls back to "wait
    # AFTER_SECONDS from now".
    age = presence_age(presence) if presence else 0.0
    state = read_json(STATE_KEY) or {}

    for server in SERVERS:
        published[server] = state.get(server, "awake")

    for server in SERVERS:
        if published.get(server) == "asleep":
            # Something outside this agent may have thawed it (a deploy unpauses
            # every backend before `compose up`); trust Docker over our state.
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
                continue            # Keep converging: a freeze that stopped early (agent restarted, or
            # the host was busy) finishes here instead of waiting for a replay.
            try:
                cg = cgroup_dir(server)
                current = int((cg / "memory.current").read_text().strip())
            except Exception:
                current = 0
            if should_reclaim(
                memory_bytes=current,
                floor_bytes=RECLAIM_FLOOR_MB * 1024 * 1024,
                deploy_pending=deploy_pending(),
                backup_running=backup_running(),
            ):
                log(f"{server}: asleep with {current // (1024 * 1024)} MiB, reclaiming")
                reclaim(server)
            continue

        # Awake: make sure it still answers, the way Spigot's watchdog used to.
        probe_liveness(server)

        if empty is None:
            empty_since.pop(server, None)
            continue

        if server not in empty:
            empty_since.pop(server, None)
            if published.get(server) != "awake":
                set_state(server, "awake")
            continue

        # Seed from the presence timestamp: it is written when the last player
        # left, so a backend that emptied before this agent started still
        # freezes on the first tick instead of waiting another full period.
        since = empty_since.setdefault(server, time.monotonic() - age)
        idle = time.monotonic() - since
        if idle < AFTER_SECONDS:
            continue

        reason = freeze_blocker(
            deploy_pending=deploy_pending(),
            maintenance_on=maintenance_on(),
            backup_running=backup_running(),
            wake_requested=wake_requested(server),
            has_rcon_password=bool(RCON_PASSWORD),
        )
        if reason:
            log(f"{server}: empty {idle:.0f}s but {reason}, staying awake")
            continue

        if DRY_RUN:
            log(f"{server}: empty {idle:.0f}s, would freeze (dry run)")
            continue

        freeze(server)


def asleep_now() -> list[str]:
    return [s for s in SERVERS if published.get(s) == "asleep"]


def run() -> None:
    log(f"hibernate agent: servers={SERVERS} after={AFTER_SECONDS:.0f}s "
        f"step={RECLAIM_STEP_MB} MiB floor={RECLAIM_FLOOR_MB} MiB dry_run={DRY_RUN}")
    if not RCON_PASSWORD:
        log("RCON_PASSWORD is empty: freezing is disabled")
    failures = 0
    while True:
        try:
            reconcile()
            break
        except Exception as e:
            failures += 1
            log(f"reconcile failed ({failures}): {type(e).__name__}: {e}")
            if failures == ALERT_AFTER_FAILURES:
                alert(
                    f"hibernate agent cannot start after {failures} attempts: "
                    f"{type(e).__name__}: {e}. Frozen backends cannot wake."
                )
            time.sleep(POLL_SECONDS)
    if failures:
        alert(f"hibernate agent recovered after {failures} failed starts")
    last_tick = 0.0
    failures = 0
    while True:
        try:
            sleeping = asleep_now()
            if sleeping:
                # A join is waiting: Velocity gives up after
                # `connection-timeout` (10 s), so the slow poll is too slow for
                # the wake. One Redis GET per sleeping server per second is
                # cheaper than any of the docker calls in tick().
                woke = [s for s in sleeping if wake_requested(s)]
                if woke:
                    for server in woke:
                        thaw(server)
                elif tick_due(
                    sleeping=True,
                    now=time.monotonic(),
                    last_tick=last_tick,
                    poll_seconds=POLL_SECONDS,
                ):
                    # Still tick while everything sleeps: a freeze interrupted by
                    # an agent restart (every deploy recreates this container)
                    # leaves the container above the reclaim floor, and tick() is
                    # what finishes the ladder (`asleep with N MiB, reclaiming`).
                    tick()
                    last_tick = time.monotonic()
                time.sleep(WAKE_POLL_SECONDS)
                failures = 0
                continue
            tick()
            last_tick = time.monotonic()
            failures = 0
        except Exception as e:
            failures += 1
            log(f"tick failed ({failures}): {type(e).__name__}: {e}")
            if failures == ALERT_AFTER_FAILURES:
                alert(
                    f"hibernate agent failing for {failures} ticks: "
                    f"{type(e).__name__}: {e}. Wakes and freezes are not running."
                )
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "once":
        tick()
    else:
        run()
