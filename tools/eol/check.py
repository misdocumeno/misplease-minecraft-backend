#!/usr/bin/env python3
"""Check that the base-image lines we float are still supported.

Every tag in `docker-compose*.yml` / `docker/server.Dockerfile` floats inside a
line (postgres 18.x, node 24.x, ...): that is how we get patches without pinning
every build. The catch is that a line only gets patches while upstream still
supports it, and nothing on the host notices when that stops.

This asks endoflife.date for each product and reports the end of support for the
line we pin. Run weekly by `.github/workflows/eol-check.yml`, which opens an
issue when a line is EOL or within WARN_DAYS of it.

Products endoflife.date does not track (restic, VictoriaMetrics, Alloy) are
listed as UNCHECKED: review those by hand when bumping. `nginx:stable-alpine` is
also unchecked - endoflife's nginx cycles are mainline releases, not the stable
tag, so the mapping would be wrong.

Usage: python tools/eol/check.py [--json]
Exit code 1 when any checked line is EOL or within WARN_DAYS.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import urllib.request

API = "https://endoflife.date/api/{product}.json"
WARN_DAYS = 30

# (what we pin, endoflife.date product, cycle prefix, where it is pinned)
PINS = [
    ("postgres:18-alpine", "postgresql", "18", "docker-compose*.yml"),
    ("redis:8-alpine", "redis", "8", "docker-compose*.yml"),
    ("node:24-alpine", "nodejs", "24", "docker-compose*.yml"),
    ("python:3.13-alpine", "python", "3.13", "tools/backup"),
    ("eclipse-temurin:25-jre", "eclipse-temurin", "25", "docker/server.Dockerfile"),
    ("grafana/grafana:13.2", "grafana", "13.2", "docker-compose.obs.yml"),
]

UNCHECKED = [
    "nginx:stable-alpine",
    "restic/restic:0.20",
    "victoriametrics/victoria-metrics",
    "victoriametrics/victoria-traces",
    "grafana/alloy",
    "victoriametrics/victoria-logs",
]


def fetch(product: str) -> list[dict]:
    with urllib.request.urlopen(API.format(product=product), timeout=30) as res:
        return json.load(res)


def cycle_matches(cycle: str, prefix: str) -> bool:
    return cycle == prefix or cycle.startswith(prefix + ".")


def parse_date(value) -> dt.date | None:
    if not value or value is True:
        return None
    if value is False:
        return None
    return dt.date.fromisoformat(str(value))


def check(name: str, product: str, prefix: str, where: str) -> dict:
    cycles = [c for c in fetch(product) if cycle_matches(str(c.get("cycle")), prefix)]
    if not cycles:
        return {
            "pin": name,
            "where": where,
            "status": "UNKNOWN",
            "detail": f"no cycle {prefix}.* in endoflife.date for {product}",
        }

    # The tag floats inside the line, so the line is supported as long as any
    # maintenance cycle of it is.
    eols = [parse_date(c.get("eol")) for c in cycles]
    bounded = [d for d in eols if d is not None]
    if any(d is None for d in eols):
        return {
            "pin": name,
            "where": where,
            "status": "OK",
            "detail": f"{product} {prefix}: still supported ({len(cycles)} cycle(s))",
        }
    if not bounded:
        return {"pin": name, "where": where, "status": "OK", "detail": f"{product} {prefix}: no EOL date"}

    eol = max(bounded)
    left = (eol - dt.date.today()).days
    if left < 0:
        status, detail = "EOL", f"{product} {prefix} ended support on {eol.isoformat()}"
    elif left <= WARN_DAYS:
        status, detail = "SOON", f"{product} {prefix} ends support on {eol.isoformat()} ({left} days)"
    else:
        status, detail = "OK", f"{product} {prefix} supported until {eol.isoformat()} ({left} days)"
    return {"pin": name, "where": where, "status": status, "detail": detail}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    args = parser.parse_args()

    results = [check(*pin) for pin in PINS]
    bad = [r for r in results if r["status"] in ("EOL", "SOON", "UNKNOWN")]

    if args.json:
        print(json.dumps({"results": results, "unchecked": UNCHECKED, "bad": len(bad)}, indent=2))
    else:
        for r in results:
            print(f"{r['status']:7} {r['pin']:28} {r['detail']}")
        if UNCHECKED:
            print("\nUnchecked (no endoflife.date product): " + ", ".join(UNCHECKED))

    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
