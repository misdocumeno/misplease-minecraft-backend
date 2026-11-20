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
