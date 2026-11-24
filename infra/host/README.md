# Host config (not applied by the deploy agent)

Files under this directory are **config for the Hyper-V VM itself**, not for the
compose stack. The deploy agent never copies them; they are applied by hand and
recorded here so git stays the source of truth for their content.

Keep this directory as small as possible. If something can be expressed in
compose or in an image, it belongs there, not here.

Apply by copying the file into the running VM (as root). From the Windows host,
`ssh` into the VM; `sudo` needs a password, so either use `sudo` yourself or run
a throwaway privileged container:

```bash
docker run --rm --privileged -v /:/host alpine:3 \
  sh -c 'mkdir -p /host/etc/systemd/system/docker.service.d \
         && install -m 644 /host/tmp/timeout.conf /host/etc/systemd/system/docker.service.d/timeout.conf'
```

| File | Destination | Applies with | Why |
|---|---|---|---|
| `systemd/docker.service.d/timeout.conf` | `/etc/systemd/system/docker.service.d/timeout.conf` | `systemctl daemon-reload` | shutting the stack down takes up to ~210 s; systemd's default 90 s always SIGKILLed dockerd mid world-save |

That is the only host-level setting we need.

## Logging: no daemon config on purpose

Container logs used to be destined for journald (`daemon.json` log driver), which
meant a docker restart and a second copy of every log in `/var/log/journal`.
Instead:

- `docker-compose.prod.yml` keeps `json-file` with `max-size: 25m, max-file: 2`,
  so Docker's copy is a small, bounded transport buffer.
- `alloy` tails every container through the docker API and pushes to
  VictoriaLogs live (`tools/obs/alloy/config.alloy`), which keeps 7 days on disk.

So the durable copy never depends on a container still existing, and no
host-level logging configuration is involved. Verify with:

```bash
docker info --format '{{.LoggingDriver}}'            # json-file
docker inspect -f '{{json .HostConfig.LogConfig}}' misplease-proxy
docker compose -f docker-compose.obs.yml logs --tail 20 alloy
```
