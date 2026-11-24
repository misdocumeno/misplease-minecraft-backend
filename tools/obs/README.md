# Observability (Victoria stack)

Lightweight metrics + logs + traces for the misplease stack. Deliberately **not**
LGTM: the game VM runs with a few hundred MiB of RAM to spare, so
Prometheus/Loki/Tempo would push it into swap.

| Piece | Role | Always on? |
|---|---|---|
| `victoriametrics` | metrics, 48 h | yes |
| `victoria-traces` | OTLP traces, 14 d | yes (idle until instrumented) |
| `alloy` | host + container metrics, container logs, OTLP receiver | yes |
| `victoria-logs` | logs, 7 d | yes |
| `grafana` | dashboards, LAN `:3001` | on demand (`ui`) |

Measured on prod: core ~ 150 MB (alloy 87+, metrics 36, traces 3, logs 2-20).
Grafana adds ~170 MB. Every container has a hard `mem_limit`, so a runaway
scraper or UI cannot take the game down with it.

**Shipping:** a push touching `docker-compose.obs.yml` or `tools/obs/**` pins the
deploy, and the deployer applies this project only (`up -d`, core services) - no
maintenance mode, no empty gate, no game restart. Mixed commits do both.

Grafana is on demand on purpose: 170 MB resident is not free on a VM that already
swaps, and it holds no data of its own - starting it takes seconds.

## Use

Everything runs as its own Compose project (`misplease-obs`), so the deployer
never touches it and it never restarts the game.

```bash
cd /home/mis/misplease

# core (metrics + traces + logs), survives reboots
docker compose -f docker-compose.obs.yml up -d

# + Grafana (UI)
docker compose -f docker-compose.obs.yml --profile ui up -d

# drop the UI again (its data is kept). Use `stop`, not `down`: `down` takes the
# whole project, logs included.
docker compose -f docker-compose.obs.yml stop grafana
```

Grafana: `http://<prod-vm>:3001` (admin / `GRAFANA_ADMIN_PASSWORD` from the
stack `.env`). Nothing is published beyond the host; metrics, traces and logs
listen on `127.0.0.1` (`8428`, `10428`, `9428`).

## Logs ship live

`alloy` discovers containers through the docker API and tails their
stdout/stderr (`loki.source.docker`), pushing to VictoriaLogs as the lines are
written. That means:

- history survives a deploy, a container recreate, or `docker rm`;
- the only copy Docker keeps is a rotated buffer (`25m x 2` per container, set in
  `docker-compose.prod.yml`), so a chatty container cannot fill the disk;
- Alloy persists read positions in `/var/lib/alloy/data`, so restarting Alloy
  resumes instead of skipping the gap.

Fields per entry: `container` (container name), `service` (compose service),
`image`, `stream` (`stdout`/`stderr`), `_msg`, `_time`. Query in Grafana, or with
LogsQL over HTTP:

```bash
curl -s 'http://127.0.0.1:9428/select/logsql/query' \
  --data-urlencode 'query=container:=misplease-main "logged in"' \
  --data-urlencode 'limit=50'
```

For a period before this was on (or if Alloy was down long enough to rotate the
buffer away), backfill from what Docker still has:

```bash
python3 tools/obs/import-logs.py --since 48h
```

## Sending traces

Alloy listens on the `misplease_default` network:

- OTLP/HTTP `http://alloy:4318`
- OTLP/gRPC `alloy:4317`

VictoriaTraces stores them with 14 d retention and Grafana reaches it through the
Tempo API (`/select/tempo`), so TraceQL and Traces Drilldown both work.

## Adding a metric or a dashboard

- Scrapers and the log pipeline live in `tools/obs/alloy/config.alloy`.
- Datasources: `tools/obs/grafana/provisioning/datasources/datasources.yml`.
- Dashboards: `tools/obs/grafana/dashboards/*.json` (file provider, `misplease`
  folder). `misplease-host.json` is the RAM/CPU/disk one.

## Rules

- Change these files here and push; do not edit them on the VM.
- Do not add this stack to `docker-compose.prod.yml`: the deployer's
  `up --remove-orphans` would fight it.
- Keep the base image tags pinned (no `latest`).
