# Observability (VictoriaMetrics stack)

Lightweight metrics + traces for the misplease stack. Deliberately **not** LGTM:
the game VM runs with ~500 MiB to spare, so Prometheus/Loki/Tempo would push it
into swap.

| Piece | Role | Always on? |
|---|---|---|
| `victoriametrics` | metrics, 48 h | yes |
| `victoria-traces` | OTLP traces, 14 d | yes (idle until instrumented) |
| `alloy` | host + container metrics, OTLP receiver | yes |
| `grafana` | dashboards, LAN `:3000` | yes |
| `victoria-logs` | logs, 7 d | on demand |

Budget: ~300 MB core, +~200 MB while the log tier is up. Hard `mem_limit`
per container, so a runaway scraper cannot take the game down with it.

## Use

Everything runs as its own Compose project (`misplease-obs`), so the deployer
never touches it and it never restarts the game.

```bash
cd /home/mis/misplease

# core (metrics + traces + Grafana), survives reboots
docker compose -f docker-compose.obs.yml up -d

# + VictoriaLogs (the log tier)
docker compose -f docker-compose.obs.yml --profile logs up -d

# drop the log tier again (its data is kept)
docker compose -f docker-compose.obs.yml --profile logs down
```

Grafana: `http://192.168.100.200:3001` (admin / `GRAFANA_ADMIN_PASSWORD` from the
stack `.env`). Metrics, traces and logs are **not** published beyond the host;
they listen on `127.0.0.1` (`8428` metrics, `10428` traces, `9428` logs).

## Logs are on demand, and that is intentional

Docker keeps every container's log on disk, so a stopped VictoriaLogs costs no
data. Start the log tier, then backfill:

```bash
python3 tools/obs/import-logs.py --since 48h
```

Fields: `_stream` (container), `_msg`, `_time`. Query in Grafana, or with
`vlogscli`-style HTTP:

```bash
curl -s 'http://127.0.0.1:9428/select/logsql/query' \
  --data-urlencode 'query=_stream:misplease-main "logged in"' \
  --data-urlencode 'limit=50'
```

## Sending traces

Alloy listens on the `misplease_default` network:

- OTLP/HTTP `http://alloy:4318`
- OTLP/gRPC `alloy:4317`

VictoriaTraces stores them with 14 d retention and Grafana reaches it through the
Tempo API (`/select/tempo`), so TraceQL and Traces Drilldown both work.

## Adding a metric or a dashboard

- Scrapers live in `tools/obs/alloy/config.alloy`.
- Datasources: `tools/obs/grafana/provisioning/datasources/datasources.yml`.
- Dashboards: `tools/obs/grafana/dashboards/*.json` (file provider, `misplease`
  folder). `misplease-host.json` is the RAM/CPU/disk one.

## Rules

- Change these files here and push; do not edit them on the VM.
- Do not add this stack to `docker-compose.prod.yml`: the deployer's
  `up --remove-orphans` would fight it.
- Keep the base image tags pinned (no `latest`).
