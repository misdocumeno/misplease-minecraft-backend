"""Pure decisions of the hibernate agent.

No docker, no redis, no cgroups, no clock: `agent.py` passes the observed values
and executes the answer. Keeping the rules here means the state machine can be
tested on any machine (no root, no VM) while the privileged I/O stays in
`agent.py`.

Why this exists: on 2026-11-19 a deploy recreated the agent in the middle of a
freeze's reclaim ladder, leaving `main` frozen at 2.4 GiB. The durable state was
fine; the fast wake-poll loop simply never ran `tick()`, which is the step that
finishes an interrupted ladder. `tick_due()` makes that rule explicit.
"""

from __future__ import annotations

from datetime import datetime, timezone
from urllib.parse import unquote, urlsplit


def redis_endpoint(url: str) -> tuple[str, int, str, str | None]:
    """Split a `redis://` URL into (host, port, db, password).

    Exists so the password can travel in `REDISCLI_AUTH` instead of argv: a
    `TimeoutExpired` repr includes the command line, which used to print the
    whole URL - credentials included - into the logs during a Redis bounce.
    """
    parts = urlsplit(url)
    return (
        parts.hostname or "localhost",
        parts.port or 6379,
        (parts.path or "/0").lstrip("/") or "0",
        unquote(parts.password) if parts.password else None,
    )


def presence_age_seconds(updated_at: str, now: datetime) -> float:
    """Seconds since the presence payload was last *changed*.

    `velocity-presence` writes RFC 3339 with nanoseconds (`...123456789Z`) and
    Go/Python clients may pad or omit the fraction. A parser that silently
    returns 0 on an odd shape would keep an empty backend awake forever, so the
    caller gets 0 only for genuinely unparseable input (and then seeds its idle
    timer from "now", which is the conservative direction).
    """
    try:
        text = updated_at.replace("Z", "+00:00")
        if "." in text:
            head, rest = text.split(".", 1)
            fraction = ""
            tz = "+00:00"
            for i, ch in enumerate(rest):
                if ch.isdigit():
                    fraction += ch
                else:
                    tz = rest[i:]
                    break
            text = f"{head}.{(fraction + '000000')[:6]}{tz}"
        then = datetime.fromisoformat(text)
    except Exception:
        return 0.0
    if then.tzinfo is None:
        then = then.replace(tzinfo=timezone.utc)
    return max(0.0, (now - then).total_seconds())


def freeze_blocker(
    *,
    deploy_pending: bool,
    maintenance_on: bool,
    backup_running: bool,
    wake_requested: bool,
    has_rcon_password: bool,
) -> str | None:
    """Why an empty server must stay awake, or None when it may freeze.

    Order matters only for the log line (first matching refusal wins).
    """
    if deploy_pending:
        return "deploy pending"
    if maintenance_on:
        return "maintenance on"
    if backup_running:
        return "backup running"
    if wake_requested:
        return "wake requested"
    if not has_rcon_password:
        return "no RCON password"
    return None


def should_reclaim(
    *,
    memory_bytes: int,
    floor_bytes: int,
    deploy_pending: bool,
    backup_running: bool,
) -> bool:
    """Finish a freeze whose reclaim ladder was interrupted (agent restart)."""
    if deploy_pending or backup_running:
        # A deploy unpauses it anyway; a backup is reading the server's files.
        return False
    return memory_bytes > floor_bytes


def tick_due(
    *,
    sleeping: bool,
    now: float,
    last_tick: float,
    poll_seconds: float,
) -> bool:
    """Whether the slow reconciler must run on this loop iteration.

    With nothing asleep the loop ticks every iteration. With everything asleep it
    polls wake keys once a second, but must still run the reconciler periodically
    (converge an interrupted freeze, adopt a container thawed outside the agent).
    """
    if not sleeping:
        return True
    return (now - last_tick) >= poll_seconds
