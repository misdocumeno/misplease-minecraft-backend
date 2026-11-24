#!/usr/bin/env python3
"""misplease deploy agent — reconcile host stack to wanted SHA.

State file (host): $MISPLEASE_ROOT/.deploy-state.json
  wanted   — pin to apply (Actions writes via: agent.py set-wanted)
  applied  — last SHA successfully applied (compose or config hot-reload)
  lastGood — last healthy *image* snapshot (sha + digests); compose path only

If every path in applied→wanted is a tracked *config* file listed in
tools/deploy/config-hot-reload.yml:
  git pin → write the changed files into the running container's /srv tree →
  RCON reload command (live). Jars never change here: this only reloads config.
Else:
  check the SHA's image tags exist → wait empty → maintenance → git pin →
  compose pull/up → health.

A config hot-reload that fails is retried up to MAX_CONFIG_RELOAD_ATTEMPTS,
then falls back to the compose path so the change still lands at the cost of a
bounce.

Redis is NOT used for wanted. Only presence empty-gate + maintenance (compose).
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("MISPLEASE_ROOT", "/home/mis/misplease"))
COMPOSE_FILE = os.environ.get("COMPOSE_FILE", "docker-compose.prod.yml")
STATE_FILE = Path(os.environ.get("DEPLOY_STATE_FILE", str(ROOT / ".deploy-state.json")))
LEGACY_LAST_GOOD = ROOT / ".deploy-last-good.json"
CONFIG_HOT_RELOAD_FILE = Path(
    os.environ.get(
        "CONFIG_HOT_RELOAD_FILE",
        str(Path(__file__).resolve().parent / "config-hot-reload.yml"),
    )
)
# Where the rules file lives in the repo, so classify can read the *wanted*
# revision of it instead of the checkout's (see load_config_hot_reload_rules).
CONFIG_HOT_RELOAD_REPO_PATH = f"tools/deploy/{CONFIG_HOT_RELOAD_FILE.name}"
MAX_CONFIG_RELOAD_ATTEMPTS = int(os.environ.get("MAX_CONFIG_RELOAD_ATTEMPTS", "3"))
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "15"))
EMPTY_SECONDS = int(os.environ.get("EMPTY_SECONDS", "60"))
HEALTH_TIMEOUT_S = int(os.environ.get("HEALTH_TIMEOUT_S", "180"))
RCON_PASSWORD = os.environ.get("RCON_PASSWORD", "")
RCON_PORT = int(os.environ.get("RCON_PORT", "25575"))
MAINTENANCE_KEY = os.environ.get("MAINTENANCE_KEY", "misplease:maintenance")
MAINTENANCE_VALUE = os.environ.get(
    "MAINTENANCE_VALUE",
    '{"enabled":true,"message":"&cServidor en mantenimiento. Probá de nuevo en unos minutos."}',
)
SERVICES = os.environ.get(
    "DEPLOY_SERVICES",
    "velocity main tech auth db-migrate db redis resource-pack hibernate",
).split()
HEALTH_SCRIPT = Path(
    os.environ.get(
        "HEALTH_SCRIPT",
        str(Path(__file__).resolve().parent / "post-deploy-health.sh"),
    )
)
OBS_COMPOSE_FILE = os.environ.get("OBS_COMPOSE_FILE", "docker-compose.obs.yml")
OBS_CONTAINERS = [
    os.environ.get("OBS_METRICS_CONTAINER", "misplease-obs-metrics"),
    os.environ.get("OBS_TRACES_CONTAINER", "misplease-obs-traces"),
    os.environ.get("OBS_LOGS_CONTAINER", "misplease-obs-logs"),
    os.environ.get("OBS_ALLOY_CONTAINER", "misplease-obs-alloy"),
]
# Paths owned by the observability project. A diff limited to these is pinned
# and applied with its own compose file: no maintenance, no empty gate, no game
# restart. The game deployer only restarts what classify says changed.
_OBS_GLOBS = [
    "docker-compose.obs.yml",
    "tools/obs/**",
]
ALERT_WEBHOOK = os.environ.get("DEPLOY_ALERT_WEBHOOK", "")

# Compose service name → Docker DNS name on the project network.
SERVER_HOST = {
    "main": "main",
    "tech": "tech",
    "velocity": "velocity",
}

_applying = False


def log(msg: str) -> None:
    print(msg, flush=True)


def alert(msg: str) -> None:
    log(f"DEPLOY ALERT: {msg}")
    if not ALERT_WEBHOOK:
        return
    try:
        body = json.dumps({"content": msg[:1800]}).encode()
        req = urllib.request.Request(
            ALERT_WEBHOOK,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=15)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        log(f"alert webhook failed: {e}")


def normalize_redis_url(url: str) -> str:
    m = re.match(r"^redis://:([^@]*)@(.+)$", url)
    if m:
        return f"redis://default:{m.group(1)}@{m.group(2)}"
    return url


def redis_url() -> str:
    url = os.environ.get("REDIS_URL", "")
    if not url:
        pw = os.environ.get("REDIS_PASSWORD", "")
        if not pw:
            raise SystemExit("REDIS_URL or REDIS_PASSWORD required")
        url = f"redis://default:{pw}@redis:6379/0"
    return normalize_redis_url(url)


def redis_cli(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["redis-cli", "--no-auth-warning", "-u", redis_url(), *args],
        check=False,
        capture_output=True,
        text=True,
    )


def maintenance_set() -> None:
    redis_cli("SET", MAINTENANCE_KEY, MAINTENANCE_VALUE)


def maintenance_clear() -> None:
    redis_cli("DEL", MAINTENANCE_KEY)


def maintenance_on() -> bool:
    r = redis_cli("EXISTS", MAINTENANCE_KEY)
    return r.returncode == 0 and r.stdout.strip() == "1"


def empty_age_seconds() -> int | None:
    r = redis_cli("GET", "misplease:presence:proxy")
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        p = json.loads(r.stdout.strip())
    except json.JSONDecodeError:
        return None
    if p.get("empty") is not True or int(p.get("count") or 0) != 0:
        return None
    raw = p.get("updatedAt") or ""
    try:
        s = raw.replace("Z", "+00:00")
        if "." in s:
            head, rest = s.split(".", 1)
            frac = ""
            tz = "+00:00"
            for i, ch in enumerate(rest):
                if ch.isdigit():
                    frac += ch
                else:
                    tz = rest[i:]
                    break
            frac = (frac + "000000")[:6]
            s = f"{head}.{frac}{tz}"
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        age = int((datetime.now(timezone.utc) - dt).total_seconds())
        return max(age, 0)
    except (ValueError, TypeError):
        return None


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def default_state() -> dict[str, Any]:
    return {
        "wanted": None,
        "applied": None,
        "lastGood": None,
        "applying": False,
        "updatedAt": None,
    }


def load_state() -> dict[str, Any]:
    if STATE_FILE.is_file():
        with STATE_FILE.open(encoding="utf-8") as f:
            data = json.load(f)
        out = default_state()
        out.update(data)
        return out

    if LEGACY_LAST_GOOD.is_file():
        with LEGACY_LAST_GOOD.open(encoding="utf-8") as f:
            legacy = json.load(f)
        sha = legacy.get("sha")
        st = default_state()
        if sha:
            st["lastGood"] = {
                "sha": sha,
                "savedAt": legacy.get("savedAt"),
                "images": legacy.get("images") or {},
            }
            st["applied"] = sha
            st["wanted"] = sha
        st["updatedAt"] = _now()
        save_state(st)
        log(f"migrated {LEGACY_LAST_GOOD.name} → {STATE_FILE.name}")
        return st

    return default_state()


def chown_like_root(path: Path) -> None:
    try:
        st = ROOT.stat()
        os.chown(path, st.st_uid, st.st_gid)
    except OSError:
        pass


def save_state(st: dict[str, Any]) -> None:
    st = dict(st)
    st["updatedAt"] = _now()
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        dir=str(STATE_FILE.parent), prefix=".deploy-state.", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(st, f, indent=2)
            f.write("\n")
        os.replace(tmp_name, STATE_FILE)
        chown_like_root(STATE_FILE)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def last_good_sha(st: dict[str, Any]) -> str | None:
    lg = st.get("lastGood")
    if isinstance(lg, dict):
        sha = lg.get("sha")
        return str(sha) if sha else None
    return None


def compose_env(image_tag: str) -> dict[str, str]:
    env = os.environ.copy()
    env["IMAGE_TAG"] = image_tag
    return env


def run(
    cmd: list[str], *, env: dict[str, str] | None = None, check: bool = True
) -> subprocess.CompletedProcess[str]:
    log("+ " + " ".join(cmd))
    return subprocess.run(
        cmd,
        cwd=str(ROOT),
        env=env or os.environ.copy(),
        check=check,
        text=True,
    )


def fix_git_ownership() -> None:
    git_dir = ROOT / ".git"
    if not git_dir.is_dir():
        return
    try:
        st = ROOT.stat()
        run(
            ["chown", "-R", f"{st.st_uid}:{st.st_gid}", str(git_dir)],
            check=False,
        )
    except OSError as e:
        log(f"chown .git skipped: {e}")


def git_fetch(sha: str) -> None:
    if not (ROOT / ".git").is_dir():
        raise RuntimeError("no .git in ROOT")
    run(
        ["git", "config", "--global", "--add", "safe.directory", str(ROOT)],
        check=False,
    )
    have = subprocess.run(
        ["git", "cat-file", "-e", f"{sha}^{{commit}}"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=False,
    )
    if have.returncode == 0:
        return
    try:
        run(["git", "fetch", "--depth=1", "origin", sha])
    finally:
        fix_git_ownership()


def git_pin(sha: str) -> None:
    if not (ROOT / ".git").is_dir():
        log("no .git in ROOT — skip git pin")
        return
    run(
        ["git", "config", "--global", "--add", "safe.directory", str(ROOT)],
        check=False,
    )
    try:
        run(["git", "fetch", "--depth=1", "origin", sha])
        run(["git", "reset", "--hard", "FETCH_HEAD"])
    finally:
        fix_git_ownership()


def git_diff_status(old_sha: str, new_sha: str) -> list[tuple[str, str, str]]:
    """[(status, path, old_path)] from old_sha to new_sha (both fetchable).

    status is git's letter code (A/M/D/R...); for renames old_path is the source.
    """
    git_fetch(old_sha)
    git_fetch(new_sha)
    r = subprocess.run(
        ["git", "diff", "--name-status", "-M", old_sha, new_sha],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=True,
    )
    changes: list[tuple[str, str, str]] = []
    for line in r.stdout.splitlines():
        parts = [p for p in line.split("\t") if p]
        if len(parts) == 2:
            changes.append((parts[0], parts[1], parts[1]))
        elif len(parts) >= 3:
            changes.append((parts[0], parts[2], parts[1]))
    return changes


def load_config_hot_reload_rules(sha: str | None = None) -> list[dict[str, Any]]:
    """Rules from `config-hot-reload.yml`.

    With `sha`, read the file as it exists at that revision (the agent may still
    be checked out on the previous commit when classifying). Falls back to disk.
    """
    text: str | None = None
    if sha:
        r = subprocess.run(
            ["git", "show", f"{sha}:{CONFIG_HOT_RELOAD_REPO_PATH}"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        if r.returncode == 0 and r.stdout.strip():
            text = r.stdout
    if text is None:
        if not CONFIG_HOT_RELOAD_FILE.is_file():
            return []
        text = CONFIG_HOT_RELOAD_FILE.read_text(encoding="utf-8")
    # Tiny YAML subset: use PyYAML if present, else a minimal loader for our file shape.
    try:
        import yaml  # type: ignore

        doc = yaml.safe_load(text) or {}
        return list(doc.get("rules") or [])
    except ImportError:
        pass
    return _parse_config_hot_reload_minimal(text)


def _parse_config_hot_reload_minimal(text: str) -> list[dict[str, Any]]:
    """Parse config-hot-reload.yml without PyYAML (id/paths/rcon)."""
    rules: list[dict[str, Any]] = []
    rule: dict[str, Any] | None = None
    section: str | None = None

    def flush_rule() -> None:
        nonlocal rule
        if rule is not None:
            rules.append(rule)
        rule = None

    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        if re.match(r"^rules:\s*$", line):
            continue
        m = re.match(r"^  - id:\s*(\S+)\s*$", line)
        if m:
            flush_rule()
            rule = {"id": m.group(1), "paths": []}
            section = None
            continue
        if rule is None:
            continue
        if re.match(r"^    paths:\s*$", line):
            section = "paths"
            continue
        m = re.match(r'^    rcon:\s*["\']?([^"\']+)["\']?\s*$', line)
        if m:
            rule["rcon"] = m.group(1).strip()
            section = None
            continue
        m = re.match(r'^    rcon_proxy:\s*["\']?([^"\']+)["\']?\s*$', line)
        if m:
            # Proxy-only override. Not a server name: Velocity and Paper register
            # different command labels for the same plugin (e.g. lpv vs lp).
            rule["rcon_proxy"] = m.group(1).strip()
            section = None
            continue
        if section == "paths":
            m = re.match(r"^      -\s+(\S+)\s*$", line)
            if m:
                rule["paths"].append(m.group(1))
    flush_rule()
    return rules


def send_rcon(host: str, command: str) -> str:
    from mcrcon import MCRcon

    with MCRcon(host, RCON_PASSWORD, port=RCON_PORT) as mcr:
        return mcr.command(command) or ""


def path_matches(globs: list[str], path: str) -> bool:
    path = path.replace("\\", "/")
    for g in globs:
        g = g.replace("\\", "/")
        if fnmatch.fnmatch(path, g):
            return True
        # 'dir/**' should also match files under dir
        if g.endswith("/**") and (path == g[:-3] or path.startswith(g[:-2])):
            return True
    return False


def obs_paths_changed(applied: str | None, wanted: str) -> bool:
    """True if the applied→wanted diff touches the observability project."""
    if not applied or applied == wanted:
        return False
    try:
        changes = git_diff_status(applied, wanted)
    except subprocess.CalledProcessError:
        return False
    return any(path_matches(_OBS_GLOBS, path) for _, path, _ in changes)


def reload_alloy() -> None:
    """Make alloy re-read its bind-mounted config.

    Alloy's config lives in the checkout, so any pin can change it, even when
    this diff does not touch the obs paths. The reload endpoint is cheap and
    idempotent and keeps the log positions in alloy's data volume; SIGHUP would
    terminate the process instead. The deployer shares `misplease_default` with
    alloy, so `alloy:12345` reaches it; if alloy is absent the reload is skipped.
    """
    try:
        req = urllib.request.Request("http://alloy:12345/-/reload", method="POST")
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read().decode("utf-8", "replace").strip()
            log(f"obs: alloy reload -> {r.status} {body}")
    except urllib.error.HTTPError as e:
        alert(f"obs: alloy rejected its config reload ({e}) — it keeps the old one")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        log(f"obs: alloy reload skipped ({e})")


def apply_obs() -> bool:
    """Bring the obs project to the pinned checkout (idempotent).

    Its own compose project and its own health: never arms maintenance, never
    waits for the empty gate and never touches a game container. Core services
    only (profiles such as `ui` stay as the operator left them).
    """
    path = ROOT / OBS_COMPOSE_FILE
    if not path.is_file():
        log(f"obs: {OBS_COMPOSE_FILE} not in checkout — skipped")
        return False
    log(f"obs: applying {OBS_COMPOSE_FILE}")
    try:
        run(["docker", "compose", "-f", OBS_COMPOSE_FILE, "up", "-d"])
    except subprocess.CalledProcessError as e:
        alert(f"obs apply failed ({e}) — game deploy unaffected")
        return False

    # `up -d` does not recreate a container whose only change is a bind-mounted
    # config file, and alloy's config is one. reload_alloy() re-reads it.
    # (Also called after every git pin: a range of commits can carry an alloy
    # change even when this particular diff does not touch the obs paths.)

    not_running = []
    for name in OBS_CONTAINERS:
        r = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", name],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        if r.stdout.strip() != "true":
            not_running.append(name)
    if not_running:
        alert(f"obs containers not running after apply: {', '.join(not_running)}")
        return False
    log("obs apply OK (core services up)")
    return True


def parse_plugins_config_path(path: str) -> tuple[str, str, str] | None:
    """If path is under a server plugins config tree, return (server, kind, rel).

    kind is 'paper' (minecraft/<server>) or 'proxy' (velocity).
    rel is relative to that server's config/plugins/.
    """
    path = path.replace("\\", "/")
    m = re.match(r"^infra/minecraft/([^/]+)/config/plugins/(.*)$", path)
    if m:
        return m.group(1), "paper", m.group(2)
    m = re.match(r"^infra/velocity/config/plugins/(.*)$", path)
    if m:
        return "velocity", "proxy", m.group(1)
    return None


def plugin_folder(rel: str) -> str | None:
    rel = rel.replace("\\", "/").strip("/")
    if not rel:
        return None
    return rel.split("/", 1)[0]


# Compose container names (docker-compose.prod.yml). The agent has docker.sock.
CONTAINERS = {
    "main": os.environ.get("MAIN_CONTAINER", "misplease-main"),
    "tech": os.environ.get("TECH_CONTAINER", "misplease-tech"),
    "velocity": os.environ.get("PROXY_CONTAINER", "misplease-proxy"),
}


def container_for(server: str) -> str:
    return CONTAINERS.get(server, f"misplease-{server}")


# Repo paths that only affect the host agent / CD pin — git reset, no game compose.
# The agent re-execs itself so the new Python process loads.
_AGENT_ONLY_GLOBS = [
    "tools/deploy/**",
    # The hibernate agent is its own container (restarted below), never a game jar.
    "tools/hibernate/**",
    # CI wiring: changes what the pipeline does, never what the running servers do.
    ".github/**",
    ".env.example",
    # Docs never change what runs: don't bounce the servers for a README.
    "*.md",
]

# Nothing here is baked into an image, so a commit touching only these has no
# image tags of its own (see image_fallback_sha).
_PIN_ONLY_GLOBS = _AGENT_ONLY_GLOBS + _OBS_GLOBS


def classify_config_hot_reload(
    applied: str | None, wanted: str
) -> list[dict[str, Any]] | None:
    """Return config hot-reload actions, [] for agent-only/empty, or None for compose.

    Each action: {rule_id, server, kind, plugin, rcon, deleted}.
    Rule `paths` are relative to config/plugins/ on every server/proxy, so the
    server is inferred from the git path: a main-only edit only touches main.
    """
    if not applied:
        return None
    try:
        changes = git_diff_status(applied, wanted)
    except subprocess.CalledProcessError as e:
        log(f"diff failed ({e}) — full deploy")
        return None
    if not changes:
        return []

    non_agent = [
        (status, path, old)
        for status, path, old in changes
        if not path_matches(_AGENT_ONLY_GLOBS, path)
    ]
    if not non_agent:
        log(
            f"agent-only diff ({len(changes)} paths) — "
            "git pin + re-exec agent (no game bounce)"
        )
        return []

    # Observability is its own project: drop it from the game diff so an obs-only
    # push never arms maintenance, and a mixed diff still hot-reloads the game
    # side. apply_obs() runs separately from the applied→wanted diff.
    game_changes = [c for c in non_agent if not path_matches(_OBS_GLOBS, c[1])]
    if not game_changes:
        log(
            f"obs-only diff ({len(non_agent)} paths) — "
            "git pin + obs project (no game bounce, no empty gate)"
        )
        return []
    non_agent = game_changes

    rules = load_config_hot_reload_rules(wanted)
    if not rules:
        return None

    actions: dict[tuple[str, str, str], dict[str, Any]] = {}
    for status, path, old in non_agent:
        parsed = parse_plugins_config_path(path)
        if not parsed:
            log(f"path not config-hot-reloadable: {path}")
            return None
        server, kind, rel = parsed
        hit = [r for r in rules if path_matches(list(r.get("paths") or []), rel)]
        if not hit:
            log(f"path not config-hot-reloadable: {path} (rel={rel})")
            return None
        plugin = plugin_folder(rel)
        if not plugin:
            log(f"path not config-hot-reloadable (empty plugin): {path}")
            return None
        # Deletes and renames: the old path must go away in the overlay too.
        files: list[str] = [] if status.startswith("D") else [rel]
        removed: list[str] = [rel] if status.startswith("D") else []
        if status.startswith("R") and old != path:
            old_parsed = parse_plugins_config_path(old)
            if old_parsed:
                old_rel = old_parsed[2]
                if plugin_folder(old_rel) == plugin:
                    removed.append(old_rel)
        for r in hit:
            key = (str(r.get("id")), server, plugin)
            action = actions.get(key)
            if action is None:
                action = {
                    "rule_id": r.get("id"),
                    "server": server,
                    "kind": kind,
                    "plugin": plugin,
                    "rcon": r.get("rcon"),
                    "files": [],
                    "deleted": [],
                }
                actions[key] = action
            action["files"].extend(f for f in files if f not in action["files"])
            action["deleted"].extend(
                d for d in removed if d not in action["deleted"]
            )
    return list(actions.values())


def reload_agent_process() -> None:
    """Re-exec the agent so the code on the /app bind mount is loaded.

    The deployer runs *inside* the container it would have to recreate, so
    calling `docker compose up --force-recreate deployer` from here kills the
    caller halfway: the compose client dies with the container and the
    replacement is left created but never started (that is exactly what happened
    on 2026-11-18). Re-exec replaces the process in place instead.

    If a future commit changes tools/deploy/requirements.txt and the import
    fails, the container exits and `restart: unless-stopped` re-runs the
    entrypoint, which reinstalls the requirements.
    """
    script = str(Path(__file__).resolve())
    log(f"re-exec agent to load {script}")
    os.execv(sys.executable, [sys.executable, "-u", script, "run"])


HIBERNATE_CONTAINER = os.environ.get("HIBERNATE_CONTAINER", "misplease-hibernate")


def reload_hibernate() -> None:
    """Restart the hibernate agent so `tools/hibernate/agent.py` is re-read.

    It runs `python /app/agent.py` from a bind mount, so a compose `up -d` does
    not recreate it when the script changes (same image, same service config).
    Cheap and safe: the agent reconciles frozen state from Docker on start.
    """
    r = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", HIBERNATE_CONTAINER],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=False,
    )
    if r.stdout.strip() != "true":
        log(f"{HIBERNATE_CONTAINER} not running - nothing to reload")
        return
    log(f"restarting {HIBERNATE_CONTAINER} to load tools/hibernate")
    run(["docker", "restart", HIBERNATE_CONTAINER], check=False)


def _safe_rel(rel: str) -> str:
    """Reject absolute / parent-escaping paths before they reach the container."""
    p = Path(rel)
    if p.is_absolute() or ".." in p.parts or not p.parts:
        raise ConfigReloadError(f"refusing unsafe config path: {rel!r}")
    return p.as_posix()


# Same substitutions entrypoint.sh does at boot, so a hot-reloaded file contains
# the real secret instead of a literal placeholder. The values come from the
# deployer's env (docker-compose.prod.yml). Anything not listed here aborts the
# reload: writing an unresolved placeholder into a live config would break the
# plugin, and guessing a secret is worse.
_PLACEHOLDERS = {
    "__MISPLEASE_DB_PASSWORD__": "DB_PASSWORD",
}


def env_value(name: str) -> str:
    """Deployer env, falling back to the stack `.env` (same file compose reads).

    The deployer only exports a few variables; the stack `.env` is already on the
    host and mounted with the repo. Reading it avoids adding secrets to the
    deployer service just for this.
    """
    value = os.environ.get(name, "")
    if value:
        return value
    env_file = ROOT / ".env"
    try:
        text = env_file.read_text(encoding="utf-8")
    except OSError:
        return ""
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        if key.strip() == name:
            return val.strip().strip('"').strip("'")
    return ""


def render_config_bytes(rel: str, raw: bytes) -> bytes:
    text = raw.decode("utf-8")
    for token, env_name in _PLACEHOLDERS.items():
        if token not in text:
            continue
        value = env_value(env_name)
        if not value:
            raise ConfigReloadError(
                f"cannot hot-reload {rel}: {token} needs {env_name} "
                "in the deployer env or the stack .env"
            )
        text = text.replace(token, value)
    left = sorted(set(re.findall(r"__MISPLEASE_[A-Z0-9_]+__", text)))
    if left:
        raise ConfigReloadError(
            f"cannot hot-reload {rel}: unresolved placeholder(s) {left}"
        )
    return text.encode("utf-8")


def sync_config_files(
    server: str,
    kind: str,
    files: list[str],
    deleted_rel: list[str] | None = None,
) -> int:
    """Merge tracked config files into the *running* server's `/srv` tree.

    The write has to happen inside the container: `/srv` is an overlay whose upper
    layer is the host `data/state`. Writing that upper directory from the host is
    not supported by overlayfs — the live mount keeps resolving the lower (image)
    inode, so the server would reload the OLD file (observed on 2026-11-18), and
    the orphan upper file then makes the next in-container create fail with
    EEXIST. Writing through the mount lets overlayfs copy up normally.

    Only the exact files git changed are written — never the whole plugin folder,
    so a sibling file with a `__MISPLEASE_*__` placeholder (LibertyBans' sql.yml,
    LuckPerms' config.yml) can never be pushed by accident. Nothing else in the
    plugin folder is touched: that is where the plugins keep their runtime data
    (homes, warps, holograms, player files...).
    """
    src_root = (
        ROOT / "infra" / "velocity" / "config" / "plugins"
        if kind == "proxy"
        else ROOT / "infra" / "minecraft" / server / "config" / "plugins"
    )
    container = container_for(server)

    # rm before cat: it also recovers a path an older agent poisoned by writing
    # straight into the upper dir (create would otherwise fail with EEXIST).
    write_script = (
        'rm -f -- "$1" && cat > "$1" && { chown 1000:1000 -- "$1" 2>/dev/null || true; }'
    )

    copied = 0
    for rel in files:
        rel = _safe_rel(rel)
        src = src_root / rel
        if not src.is_file():
            raise ConfigReloadError(f"missing config source file: {src}")
        r = subprocess.run(
            ["docker", "exec", "-i", container, "sh", "-c", write_script, "sh",
             f"/srv/plugins/{rel}"],
            input=render_config_bytes(rel, src.read_bytes()),
            capture_output=True,
        )
        if r.returncode != 0:
            raise ConfigReloadError(
                f"write /srv/plugins/{rel} in {container} failed: "
                f"{r.stderr.decode('utf-8', 'replace').strip()[:300]}"
            )
        copied += 1

    for rel in deleted_rel or []:
        rel = _safe_rel(rel)
        r = subprocess.run(
            ["docker", "exec", container, "rm", "-f", "--", f"/srv/plugins/{rel}"],
            capture_output=True,
        )
        if r.returncode != 0:
            raise ConfigReloadError(
                f"delete /srv/plugins/{rel} in {container} failed: "
                f"{r.stderr.decode('utf-8', 'replace').strip()[:300]}"
            )
        log(f"deleted (removed in git): {rel}")

    log(
        f"config sync {server} ({container}): {copied} file(s) written, "
        f"{len(deleted_rel or [])} deleted, runtime data kept"
    )
    return copied


def _config_reload_attempts(st: dict[str, Any], sha: str) -> int:
    hr = st.get("configHotReload") or {}
    if not isinstance(hr, dict) or hr.get("sha") != sha:
        return 0
    try:
        return int(hr.get("attempts") or 0)
    except (TypeError, ValueError):
        return 0


def record_config_reload_failure(sha: str, reason: str) -> None:
    st = load_state()
    st["configHotReload"] = {
        "sha": sha,
        "attempts": _config_reload_attempts(st, sha) + 1,
        "reason": reason[:300],
    }
    st["applying"] = False
    save_state(st)


class ConfigReloadError(RuntimeError):
    """Hot-reload failed; the caller decides between retry and a compose bounce."""


def apply_config_hot_reload(sha: str, actions: list[dict[str, Any]]) -> bool:
    """Sync plugin config trees + run each plugin's reload command over RCON.

    actions entries: {rule_id, server, kind, plugin, rcon, deleted}.
    """
    global _applying
    _applying = True
    st = load_state()
    prev_applied = st.get("applied")
    st["applying"] = True
    save_state(st)
    try:
        if not actions:
            # Empty diff or agent-only: pin checkout; re-exec so the new code
            # from the /app bind mount replaces this process.
            git_pin(sha)
            reload_alloy()
            if obs_paths_changed(prev_applied, sha):
                apply_obs()
            st = load_state()
            st["applied"] = sha
            st.pop("configHotReload", None)
            st["applying"] = False
            save_state(st)
            log(f"agent-only/git-pin OK {sha} — re-exec agent")
            reload_hibernate()
            reload_agent_process()
            return True

        if not RCON_PASSWORD:
            raise ConfigReloadError(
                "config hot-reload needs RCON_PASSWORD in the deployer env"
            )

        log(
            "config hot-reload applying "
            f"{sha} actions={[a.get('rule_id') for a in actions]}"
        )
        # Pin first so the checkout (and the rules file on disk) matches wanted.
        git_pin(sha)
        reload_alloy()
        if obs_paths_changed(prev_applied, sha):
            apply_obs()
        rules_by_id = {
            str(r.get("id")): r
            for r in load_config_hot_reload_rules(sha)
            if r.get("id")
        }

        for action in actions:
            server = str(action.get("server") or "")
            kind = str(action.get("kind") or "")
            plugin = str(action.get("plugin") or "")
            rule = rules_by_id.get(str(action.get("rule_id") or ""))
            cmd = (rule or {}).get("rcon") or action.get("rcon")
            if kind == "proxy" and (rule or {}).get("rcon_proxy"):
                cmd = rule["rcon_proxy"]
            if not server or not kind or not plugin:
                raise ConfigReloadError(f"action incomplete: {action}")
            if not cmd:
                raise ConfigReloadError(
                    f"rule {action.get('rule_id')} has no rcon command"
                )
            if kind == "proxy" and ":" in str(cmd):
                # Velocity has no plugin:command namespacing (it answers "No such
                # command"), so proxy rules must stay bare. Catch the mistake here
                # instead of failing at runtime.
                log(f"note: proxy command is not namespaced (Velocity): {cmd}")
            sync_config_files(
                server,
                kind,
                files=list(action.get("files") or []),
                deleted_rel=list(action.get("deleted") or []),
            )
            host = SERVER_HOST.get(server, server)
            log(f"rcon {server} ({host}:{RCON_PORT}): {cmd}")
            try:
                resp = send_rcon(host, str(cmd))
            except Exception as e:
                raise ConfigReloadError(f"RCON failed on {server}: {e}") from e
            text = (resp or "").strip()
            if text:
                log(f"rcon response: {text[:500]}")
            # Paper: "Unknown or incomplete command"; Velocity: "No such command".
            if "Unknown or incomplete command" in text or "No such command" in text:
                raise ConfigReloadError(
                    f"RCON rejected `{cmd}` on {server}: {text[:200]}"
                )

        st = load_state()
        st["applied"] = sha
        st.pop("configHotReload", None)
        st["applying"] = False
        save_state(st)
        log(f"config hot-reload OK {sha}")
        return True
    except Exception as e:  # ConfigReloadError or an unexpected failure
        alert(f"config hot-reload FAILED {sha}: {e}")
        record_config_reload_failure(sha, str(e))
        return False
    finally:
        _applying = False
        st = load_state()
        if st.get("applying"):
            st["applying"] = False
            save_state(st)


def image_digest(service: str) -> str | None:
    r = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE, "ps", "-q", service],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=False,
    )
    cid = (r.stdout or "").strip()
    if not cid:
        return None
    r2 = subprocess.run(
        ["docker", "inspect", "-f", "{{index .Image}}", cid],
        capture_output=True,
        text=True,
        check=False,
    )
    out = (r2.stdout or "").strip()
    return out or None


def pin_dotenv_image_tag(tag: str) -> None:
    path = ROOT / ".env"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    if re.search(r"^IMAGE_TAG=", text, re.M):
        text = re.sub(
            r"^IMAGE_TAG=.*$", f"IMAGE_TAG={tag}", text, count=1, flags=re.M
        )
    else:
        text = text.rstrip() + f"\nIMAGE_TAG={tag}\n"
    path.write_text(text, encoding="utf-8")
    log(f"pinned IMAGE_TAG={tag} in .env")


def unpause_all() -> None:
    """Unpause hibernated containers before compose stops/recreates them.

    Docker refuses to stop a paused container, and an idle backend may be
    frozen (cgroup freezer) by the sleep feature. Waking it here is the same
    thing a player join does; the check is per container and idempotent.
    """
    for name in CONTAINERS.values():
        r = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Paused}}", name],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        if r.stdout.strip() == "true":
            log(f"unpausing {name} before compose apply")
            run(["docker", "unpause", name], check=False)


def missing_image_tags(tag: str) -> list[str]:
    """Image tags of the compose file that the registry does not have yet.

    `wanted` can name a SHA whose images never got published (smoke failed) or
    are still building. Checked before the empty gate/maintenance so that pin
    costs no downtime: 8762418 armed maintenance and then failed every pull for
    25 minutes. `docker manifest inspect` is a manifest HEAD, no layer download.
    """
    cfg = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE, "config", "--images"],
        cwd=str(ROOT),
        env=compose_env(tag),
        capture_output=True,
        text=True,
        check=False,
    )
    if cfg.returncode != 0:
        # Cannot resolve: let the compose pull surface the real error.
        return []
    images = sorted(
        {line.strip() for line in cfg.stdout.splitlines() if line.strip().endswith(f":{tag}")}
    )
    missing: list[str] = []
    for image in images:
        r = subprocess.run(
            ["docker", "manifest", "inspect", image],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        if r.returncode != 0:
            missing.append(image)
    return missing


def compose_pull_up(tag: str) -> str:
    """Pull + up with IMAGE_TAG=$sha. Missing tags → raise and retry (no :dev fallback)."""
    env = compose_env(tag)
    log(f"IMAGE_TAG={tag}")
    pull = subprocess.run(
        ["docker", "compose", "-f", COMPOSE_FILE, "pull", *SERVICES],
        cwd=str(ROOT),
        env=env,
        check=False,
        text=True,
    )
    used = tag
    if pull.returncode != 0:
        # Wait for images.yml promote-sha-tags; never apply a floating :dev tag.
        raise RuntimeError(
            f"compose pull IMAGE_TAG={tag} failed — tags not ready yet "
            "(will retry; not falling back to :dev for compose applies)"
        )
    run(
        [
            "docker",
            "compose",
            "-f",
            COMPOSE_FILE,
            "up",
            "-d",
            "--remove-orphans",
            *SERVICES,
        ],
        env=env,
    )
    return used


def image_fallback_sha(wanted: str) -> str | None:
    """Newest ancestor of `wanted` whose image tags exist, if the tail is pin-only.

    A pin-only push (CI/agent/obs only) has no image tags on purpose: nothing
    rebuilt. If an image-affecting commit was still building when it landed, the
    pin skips ahead of where the images are and the compose path would wait for
    tags that will never be published. Applying the newest tagged ancestor first
    is safe only when every path between it and `wanted` is pin-only; otherwise
    a build for one of those commits failed or is still running, and waiting is
    exactly right.
    """
    if not (ROOT / ".git").is_dir():
        return None
    run(["git", "config", "--global", "--add", "safe.directory", str(ROOT)], check=False)
    # Forced refspec: this clone is shallow and its `origin/main` can be a commit
    # from before a history rewrite. A plain `main:refs/remotes/origin/main` fetch
    # is then rejected as non-fast-forward, leaving the ref stale, so the
    # rev-list below never contains `wanted` and the fallback silently returns
    # None - the deploy waits forever for tags that will never exist.
    run(
        [
            "git", "fetch", "--quiet", "--depth=40",
            "origin", "+main:refs/remotes/origin/main",
        ],
        check=False,
    )
    try:
        revs = subprocess.run(
            ["git", "rev-list", "--first-parent", "--max-count=30", "origin/main"],
            cwd=str(ROOT), capture_output=True, text=True, check=True,
        ).stdout.split()
    except subprocess.CalledProcessError:
        return None
    if wanted not in revs:
        return None
    for cand in revs[revs.index(wanted) + 1:][:10]:
        if missing_image_tags(cand):
            continue
        try:
            paths = subprocess.run(
                ["git", "diff", "--name-only", cand, wanted],
                cwd=str(ROOT), capture_output=True, text=True, check=True,
            ).stdout.split()
        except subprocess.CalledProcessError:
            return None
        if not paths or all(path_matches(_PIN_ONLY_GLOBS, p) for p in paths):
            return cand
        return None
    return None


def run_health() -> bool:
    if not HEALTH_SCRIPT.is_file():
        log(f"missing health script: {HEALTH_SCRIPT}")
        return False
    r = subprocess.run(
        ["bash", str(HEALTH_SCRIPT), COMPOSE_FILE, str(HEALTH_TIMEOUT_S)],
        cwd=str(ROOT),
        check=False,
        text=True,
    )
    return r.returncode == 0


def write_last_good(st: dict[str, Any], sha: str) -> dict[str, Any]:
    st = dict(st)
    st["lastGood"] = {
        "sha": sha,
        "savedAt": _now(),
        "images": {
            "velocity": image_digest("velocity"),
            "main": image_digest("main"),
            "tech": image_digest("tech"),
            "auth": image_digest("auth"),
        },
    }
    return st


def apply_compose(sha: str) -> bool:
    global _applying
    _applying = True
    st = load_state()
    prev_applied = st.get("applied")
    st["applying"] = True
    save_state(st)
    try:
        log(f"enabling maintenance — compose applying {sha}")
        maintenance_set()
        time.sleep(2)
        if empty_age_seconds() is None:
            log("players present after maintenance arm — abort apply attempt")
            maintenance_clear()
            return False

        unpause_all()
        git_pin(sha)
        reload_alloy()
        if obs_paths_changed(prev_applied, sha):
            apply_obs()
        used_tag = compose_pull_up(sha)
        ok = run_health()
        st = load_state()
        if ok:
            pin_dotenv_image_tag(used_tag)
            st["applied"] = sha
            st.pop("configHotReload", None)
            st = write_last_good(st, sha)
            st["applying"] = False
            save_state(st)
            maintenance_clear()
            reload_hibernate()
            log(f"compose apply OK {sha}")
            return True

        log(f"compose apply FAILED health for {sha}")
        st["applied"] = sha
        if st.get("wanted") == sha:
            lg = last_good_sha(st)
            if lg and lg != sha:
                log(f"wanted ← lastGood {lg}")
                st["wanted"] = lg
                st["applying"] = False
                save_state(st)
                maintenance_clear()
                alert(f"Deploy {sha} failed health — wanted rewound to {lg}")
                return False
            alert(
                f"Deploy {sha} failed health and no usable lastGood — maintenance left ON"
            )
            st["applying"] = False
            save_state(st)
            return False
        st["applying"] = False
        save_state(st)
        maintenance_clear()
        alert(f"Deploy {sha} failed health — newer wanted={st.get('wanted')}")
        return False
    except Exception:
        maintenance_clear()
        raise
    finally:
        _applying = False
        st = load_state()
        if st.get("applying"):
            st["applying"] = False
            save_state(st)


def recover_crashed_apply() -> None:
    st = load_state()
    if st.get("applying"):
        log("stale applying=true on startup — clearing maintenance/lock")
        maintenance_clear()
        st["applying"] = False
        save_state(st)


_missing_tags_notice: dict[str, str] = {}


def tick() -> None:
    st = load_state()
    wanted = st.get("wanted")
    applied = st.get("applied")

    if not wanted:
        return

    if maintenance_on() and not _applying:
        return

    if wanted == applied:
        return

    wanted_s = str(wanted)
    applied_s = str(applied) if applied else None

    matched = classify_config_hot_reload(applied_s, wanted_s)
    if matched is not None:
        attempts = _config_reload_attempts(st, wanted_s)
        if matched and attempts >= MAX_CONFIG_RELOAD_ATTEMPTS:
            log(
                f"config hot-reload failed {attempts}x for {wanted_s} — "
                "falling back to a compose deploy"
            )
        else:
            log(
                f"classify config hot-reload wanted={wanted_s} "
                f"rules={[r.get('rule_id') for r in matched]}"
            )
            apply_config_hot_reload(wanted_s, matched)
            return

    missing = missing_image_tags(wanted_s)
    if missing:
        fallback = image_fallback_sha(wanted_s)
        if fallback is None:
            note = f"{wanted_s}|{','.join(missing)}"
            if _missing_tags_notice.get("notice") != note:
                _missing_tags_notice["notice"] = note
                log(
                    f"images not published yet for {wanted_s} "
                    f"({', '.join(missing)}) — waiting, maintenance stays off"
                )
            return
        # Pin-only tail (CI/agent/obs): those commits never get images. Apply the
        # newest tagged ancestor now; the next tick sees a pin-only diff and only
        # checks the repo out + reloads the containers.
        log(
            f"no images for {wanted_s[:7]} (pin-only tail) — applying "
            f"{fallback[:7]} first, then the agent/CI commits on top"
        )
        wanted_s = fallback

    age = empty_age_seconds()
    if age is None:
        return
    if age < EMPTY_SECONDS:
        log(
            f"pending compose wanted={wanted_s} applied={applied_s} "
            f"— empty {age}s / {EMPTY_SECONDS}s"
        )
        return

    log(f"proxy empty {age}s — compose applying wanted={wanted_s}")
    apply_compose(wanted_s)


def loop() -> None:
    log(
        f"deploy agent start root={ROOT} state={STATE_FILE} "
        f"poll={POLL_SECONDS}s empty>={EMPTY_SECONDS}s "
        f"config-hot-reload={CONFIG_HOT_RELOAD_FILE}"
    )
    if not ROOT.is_dir():
        raise SystemExit(f"MISPLEASE_ROOT missing: {ROOT}")
    try:
        import mcrcon  # noqa: F401
    except ImportError as e:
        raise SystemExit(
            f"mcrcon missing in deployer (pip install -r requirements.txt): {e}"
        ) from e
    recover_crashed_apply()
    while True:
        try:
            tick()
        except subprocess.CalledProcessError as e:
            alert(f"deploy agent command failed: {e}")
        except Exception as e:
            alert(f"deploy agent error: {e}")
            log(f"error: {e}")
        time.sleep(POLL_SECONDS)


def cmd_set_wanted(sha: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise SystemExit(f"invalid sha (need full 40 hex chars): {sha}")
    st = load_state()
    prev = st.get("wanted")
    st["wanted"] = sha
    save_state(st)
    log(f"wanted {prev} → {sha} ({STATE_FILE})")
    if sha != st.get("applied"):
        alert(f"Pending deploy wanted={sha} (applied={st.get('applied')})")


def main() -> None:
    parser = argparse.ArgumentParser(description="misplease deploy agent")
    sub = parser.add_subparsers(dest="cmd")

    p_set = sub.add_parser("set-wanted", help="Write wanted SHA (Actions / manual)")
    p_set.add_argument("sha")

    sub.add_parser("status", help="Print state file")
    sub.add_parser("run", help="Reconcile loop (default)")

    p_class = sub.add_parser(
        "classify",
        help="Print what applied→wanted would do (read-only, no side effects)",
    )
    p_class.add_argument("applied")
    p_class.add_argument("wanted")

    args = parser.parse_args()
    cmd = args.cmd or "run"

    if cmd == "set-wanted":
        cmd_set_wanted(args.sha)
    elif cmd == "classify":
        actions = classify_config_hot_reload(args.applied, args.wanted)
        if actions is None:
            sys.stdout.write("compose deploy (not config-hot-reloadable)\n")
        else:
            json.dump(actions, sys.stdout, indent=2)
            sys.stdout.write("\n")
    elif cmd == "status":
        json.dump(load_state(), sys.stdout, indent=2)
        sys.stdout.write("\n")
    elif cmd == "run":
        loop()
    else:
        parser.error(f"unknown cmd {cmd}")


if __name__ == "__main__":
    main()
