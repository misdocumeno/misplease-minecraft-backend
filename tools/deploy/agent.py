#!/usr/bin/env python3
"""misplease deploy agent — reconcile host stack to wanted SHA.

State file (host): $MISPLEASE_ROOT/.deploy-state.json
  wanted   — pin to apply (Actions writes via: agent.py set-wanted)
  applied  — last SHA successfully applied (compose or hot-reload)
  lastGood — last healthy *image* snapshot (sha + digests); compose path only

If every path in applied→wanted matches tools/deploy/hot-reload.yml:
  git pin → sync config into overlay upper (data/state) → RCON reload (live).
Else:
  wait empty → maintenance → git pin → compose pull/up → health.

Redis is NOT used for wanted. Only presence empty-gate + maintenance (compose).
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import shutil
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
# Container runs `python /app/agent.py`; keep sibling imports working.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import RconError, rcon_command  # noqa: E402
COMPOSE_FILE = os.environ.get("COMPOSE_FILE", "docker-compose.prod.yml")
STATE_FILE = Path(os.environ.get("DEPLOY_STATE_FILE", str(ROOT / ".deploy-state.json")))
LEGACY_LAST_GOOD = ROOT / ".deploy-last-good.json"
HOT_RELOAD_FILE = Path(
    os.environ.get(
        "HOT_RELOAD_FILE",
        str(Path(__file__).resolve().parent / "hot-reload.yml"),
    )
)
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
    "DEPLOY_SERVICES", "velocity main tech auth db-migrate db redis"
).split()
HEALTH_SCRIPT = Path(
    os.environ.get(
        "HEALTH_SCRIPT",
        str(Path(__file__).resolve().parent / "post-deploy-health.sh"),
    )
)
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


def git_diff_names(old_sha: str, new_sha: str) -> list[str]:
    """List paths changed from old_sha to new_sha (both must be fetchable)."""
    git_fetch(old_sha)
    git_fetch(new_sha)
    r = subprocess.run(
        ["git", "diff", "--name-only", old_sha, new_sha],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=True,
    )
    return [ln.strip() for ln in r.stdout.splitlines() if ln.strip()]


def load_hot_reload_rules() -> list[dict[str, Any]]:
    if not HOT_RELOAD_FILE.is_file():
        return []
    # Tiny YAML subset: use PyYAML if present, else a minimal loader for our file shape.
    text = HOT_RELOAD_FILE.read_text(encoding="utf-8")
    try:
        import yaml  # type: ignore

        doc = yaml.safe_load(text) or {}
        return list(doc.get("rules") or [])
    except ImportError:
        pass
    return _parse_hot_reload_minimal(text)


def _parse_hot_reload_minimal(text: str) -> list[dict[str, Any]]:
    """Parse the checked-in hot-reload.yml without PyYAML."""
    rules: list[dict[str, Any]] = []
    rule: dict[str, Any] | None = None
    section: str | None = None
    target: dict[str, Any] | None = None
    sync: dict[str, str] | None = None

    def flush_target() -> None:
        nonlocal target, sync
        if rule is not None and target is not None:
            if sync:
                target["sync"] = sync
            rule.setdefault("targets", []).append(target)
        target = None
        sync = None

    def flush_rule() -> None:
        nonlocal rule
        flush_target()
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
            rule = {"id": m.group(1), "paths": [], "targets": []}
            section = None
            continue
        if rule is None:
            continue
        if re.match(r"^    paths:\s*$", line):
            section = "paths"
            continue
        if re.match(r"^    targets:\s*$", line):
            section = "targets"
            continue
        if section == "paths":
            m = re.match(r'^      -\s+(\S+)\s*$', line)
            if m:
                rule["paths"].append(m.group(1))
            continue
        if section == "targets":
            m = re.match(r"^      - server:\s*(\S+)\s*$", line)
            if m:
                flush_target()
                target = {"server": m.group(1)}
                sync = None
                continue
            if target is None:
                continue
            if re.match(r"^        sync:\s*$", line):
                sync = {}
                continue
            m = re.match(r"^          from:\s+(\S+)\s*$", line)
            if m and sync is not None:
                sync["from"] = m.group(1)
                continue
            m = re.match(r"^          to:\s+(\S+)\s*$", line)
            if m and sync is not None:
                sync["to"] = m.group(1)
                continue
            m = re.match(r"^        rcon:\s+(\S+)\s*$", line)
            if m:
                target["rcon"] = m.group(1)
                continue
    flush_rule()
    return rules


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


def classify_hot_reload(applied: str | None, wanted: str) -> list[dict[str, Any]] | None:
    """Return matched rules if hot-reload-only; None if full compose deploy required."""
    if not applied:
        return None
    try:
        changed = git_diff_names(applied, wanted)
    except subprocess.CalledProcessError as e:
        log(f"diff failed ({e}) — full deploy")
        return None
    if not changed:
        # Same tree; treat as hot no-op success path (caller marks applied).
        return []
    rules = load_hot_reload_rules()
    if not rules:
        return None
    matched: list[dict[str, Any]] = []
    for path in changed:
        hit = [r for r in rules if path_matches(list(r.get("paths") or []), path)]
        if not hit:
            log(f"path not hot-reloadable: {path}")
            return None
        for r in hit:
            if r not in matched:
                matched.append(r)
    return matched


def sync_tree(src_rel: str, dest_rel: str) -> None:
    src = ROOT / src_rel
    dest = ROOT / dest_rel
    if not src.is_dir():
        raise FileNotFoundError(f"hot-reload sync missing source: {src}")
    dest.mkdir(parents=True, exist_ok=True)
    # Replace dest contents with src (tracked config only).
    for child in dest.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    for child in src.iterdir():
        target = dest / child.name
        if child.is_dir():
            shutil.copytree(child, target)
        else:
            shutil.copy2(child, target)
    log(f"synced {src_rel} → {dest_rel}")


def apply_hot_reload(sha: str, rules: list[dict[str, Any]]) -> bool:
    global _applying
    _applying = True
    st = load_state()
    st["applying"] = True
    save_state(st)
    try:
        if not rules:
            git_pin(sha)
            st = load_state()
            st["applied"] = sha
            st["applying"] = False
            save_state(st)
            log(f"hot-reload no-op (empty diff) {sha}")
            return True

        if not RCON_PASSWORD:
            alert("hot-reload needs RCON_PASSWORD in deployer env — abort")
            return False

        log(f"hot-reload applying {sha} rules={[r.get('id') for r in rules]}")
        git_pin(sha)

        for rule in rules:
            for target in rule.get("targets") or []:
                sync = target.get("sync") or {}
                src = sync.get("from")
                dest = sync.get("to")
                if src and dest:
                    sync_tree(str(src), str(dest))
                cmd = target.get("rcon")
                server = target.get("server")
                if not cmd or not server:
                    continue
                host = SERVER_HOST.get(str(server), str(server))
                log(f"rcon {server} ({host}:{RCON_PORT}): {cmd}")
                try:
                    resp = rcon_command(host, RCON_PASSWORD, str(cmd), port=RCON_PORT)
                    if resp.strip():
                        log(f"rcon response: {resp.strip()[:500]}")
                except (RconError, OSError, TimeoutError) as e:
                    alert(f"hot-reload RCON failed on {server}: {e}")
                    return False

        st = load_state()
        st["applied"] = sha
        st["applying"] = False
        save_state(st)
        log(f"hot-reload OK {sha}")
        return True
    except Exception:
        raise
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


def compose_pull_up(tag: str) -> str:
    """Pull + up. Prefer :$tag. Fall back to :dev only for agent-only commits
    (no images workflow). If :$tag is missing, raise so we do not mark applied
    against stale :dev from a race with images still building."""
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
        # Agent-only commits never get :$sha tags — :dev is intentional.
        # Image-building commits must wait for promote-sha-tags; do not apply
        # stale :dev and then no-op when set-wanted repeats the same sha.
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

        git_pin(sha)
        used_tag = compose_pull_up(sha)
        ok = run_health()
        st = load_state()
        if ok:
            pin_dotenv_image_tag(used_tag)
            st["applied"] = sha
            st = write_last_good(st, sha)
            st["applying"] = False
            save_state(st)
            maintenance_clear()
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

    matched = classify_hot_reload(applied_s, wanted_s)
    if matched is not None:
        log(f"classify hot-reload wanted={wanted_s} rules={[r.get('id') for r in matched]}")
        apply_hot_reload(wanted_s, matched)
        return

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
        f"poll={POLL_SECONDS}s empty>={EMPTY_SECONDS}s hot-reload={HOT_RELOAD_FILE}"
    )
    if not ROOT.is_dir():
        raise SystemExit(f"MISPLEASE_ROOT missing: {ROOT}")
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

    args = parser.parse_args()
    cmd = args.cmd or "run"

    if cmd == "set-wanted":
        cmd_set_wanted(args.sha)
    elif cmd == "status":
        json.dump(load_state(), sys.stdout, indent=2)
        sys.stdout.write("\n")
    elif cmd == "run":
        loop()
    else:
        parser.error(f"unknown cmd {cmd}")


if __name__ == "__main__":
    main()
