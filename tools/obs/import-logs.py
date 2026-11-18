#!/usr/bin/env python3
"""Backfill container logs into VictoriaLogs.

The log tier is on demand (`--profile deep`), so starting it late must not lose
history: docker already keeps the logs on disk. This dumps them, timestamps
included, into VictoriaLogs.

    python3 tools/obs/import-logs.py                    # misplease*, 24h
    python3 tools/obs/import-logs.py --since 72h
    python3 tools/obs/import-logs.py --containers misplease-main,misplease-tech
    python3 tools/obs/import-logs.py --all              # every container on the host

Requires: python3 (stdlib only), the docker CLI, and VictoriaLogs running on
127.0.0.1:9428 (`--profile deep`). Safe to re-run: duplicates show up as repeats,
which is honest (docker cannot tell us a log line's identity).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:9428"
BATCH = 500


def docker(*args: str) -> str:
    proc = subprocess.run(["docker", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"docker {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def containers(all_containers: bool, explicit: str | None) -> list[str]:
    if explicit:
        return [c.strip() for c in explicit.split(",") if c.strip()]
    names = docker("ps", "-a", "--format", "{{.Names}}").split()
    if all_containers:
        return sorted(names)
    return sorted(n for n in names if n.startswith("misplease"))


def rows(container: str, since: str):
    """docker logs line -> VictoriaLogs jsonline row."""
    out = docker("logs", "--timestamps", "--since", since, container)
    for line in out.splitlines():
        ts, _, msg = line.partition(" ")
        if not msg:
            continue
        # docker prints RFC3339Nano, which VictoriaLogs accepts as _time.
        yield {
            "_time": ts,
            "_stream": container,
            "_msg": msg,
        }


def post(url: str, batch: list[dict]) -> None:
    body = "\n".join(json.dumps(r, ensure_ascii=False) for r in batch).encode()
    req = urllib.request.Request(
        f"{url}/insert/jsonline?_stream_fields=_stream",
        data=body,
        headers={"Content-Type": "application/stream+json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        if resp.status >= 300:
            raise RuntimeError(f"VictoriaLogs answered {resp.status}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--since", default="24h", help="docker logs --since (default 24h)")
    ap.add_argument("--containers", help="comma separated; default misplease*")
    ap.add_argument("--all", action="store_true", help="every container on the host")
    args = ap.parse_args()

    names = containers(args.all, args.containers)
    if not names:
        print("no containers matched", file=sys.stderr)
        return 1

    total = 0
    for name in names:
        batch: list[dict] = []
        count = 0
        try:
            for row in rows(name, args.since):
                batch.append(row)
                if len(batch) >= BATCH:
                    post(args.url, batch)
                    count += len(batch)
                    batch = []
            if batch:
                post(args.url, batch)
                count += len(batch)
        except (urllib.error.URLError, RuntimeError) as e:
            print(f"{name}: FAILED after {count} rows: {e}", file=sys.stderr)
            return 2
        print(f"{name}: {count} rows")
        total += count

    print(f"done: {total} rows from {len(names)} containers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
