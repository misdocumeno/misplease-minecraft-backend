#!/usr/bin/env python3
"""misplease deploy agent — reconcile host stack to wanted SHA.

State file (host): $MISPLEASE_ROOT/.deploy-state.json
  wanted   — pin to apply (Actions writes via: agent.py set-wanted)
  applied  — last SHA we put on the wire (success or failed attempt)
  lastGood — last healthy snapshot (sha + image digests)

Redis is NOT used for wanted. Only for:
  - presence empty-gate (misplease:presence:proxy)
  - maintenance flag (Velocity plugin)

Loop:
  Redis maintenance set and not mid-apply → no-op
  wanted == applied → no-op
  else empty ≥ EMPTY_SECONDS → maintenance → git pin → pull/up → health
  success → lastGood/applied = sha, clear maintenance
  fail → applied = sha; if wanted still that sha → wanted = lastGood;
         next loop applies lastGood (maintenance cleared so reconcile can run)
"""

from __future__ import annotations

import argparse
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
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "15"))
EMPTY_SECONDS = int(os.environ.get("EMPTY_SECONDS", "60"))
HEALTH_TIMEOUT_S = int(os.environ.get("HEALTH_TIMEOUT_S", "180"))
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
    """Seconds proxy has been empty, or None if busy / redis/presence unavailable."""
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
    """Match ownership of MISPLEASE_ROOT (deployer is root; host user is usually mis)."""
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
    """Deployer runs as root; leave .git owned like the stack dir so host `git pull` works."""
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
        log(f"pull :{tag} failed — falling back to IMAGE_TAG=dev")
        used = "dev"
        env = compose_env(used)
        run(
            ["docker", "compose", "-f", COMPOSE_FILE, "pull", *SERVICES],
            env=env,
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


def apply_sha(sha: str) -> bool:
    global _applying
    _applying = True
    st = load_state()
    st["applying"] = True
    save_state(st)
    try:
        log(f"enabling maintenance — applying {sha}")
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
            # Prefer immutable sha in .env; "dev" fallback still records git sha.
            pin_dotenv_image_tag(used_tag)
            st["applied"] = sha
            st = write_last_good(st, sha)
            st["applying"] = False
            save_state(st)
            maintenance_clear()
            log(f"apply OK {sha}")
            return True

        log(f"apply FAILED health for {sha}")
        st["applied"] = sha
        if st.get("wanted") == sha:
            lg = last_good_sha(st)
            if lg and lg != sha:
                log(f"wanted ← lastGood {lg}")
                st["wanted"] = lg
                st["applying"] = False
                save_state(st)
                # Clear so the next tick can arm maintenance again for lastGood.
                maintenance_clear()
                alert(f"Deploy {sha} failed health — wanted rewound to {lg}")
                return False
            alert(
                f"Deploy {sha} failed health and no usable lastGood — maintenance left ON"
            )
            st["applying"] = False
            save_state(st)
            return False
        # Newer wanted already set — leave it; clear maintenance for next apply.
        st["applying"] = False
        save_state(st)
        maintenance_clear()
        alert(f"Deploy {sha} failed health — newer wanted={st.get('wanted')}")
        return False
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

    age = empty_age_seconds()
    if age is None:
        return
    if age < EMPTY_SECONDS:
        log(
            f"pending deploy wanted={wanted} applied={applied} "
            f"— empty {age}s / {EMPTY_SECONDS}s"
        )
        return

    log(f"proxy empty {age}s — applying wanted={wanted}")
    apply_sha(str(wanted))


def loop() -> None:
    log(
        f"deploy agent start root={ROOT} state={STATE_FILE} "
        f"poll={POLL_SECONDS}s empty>={EMPTY_SECONDS}s"
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
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        raise SystemExit(f"invalid sha: {sha}")
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
