# Host config (not applied by the deploy agent)

Files under this directory are **config for the Hyper-V VM itself**, not for the
compose stack. The deploy agent never copies them; they are applied by hand and
recorded here so git stays the source of truth for their content.

Apply by copying the file into the running VM's `/etc` (as root) and reloading
the unit that owns it. From the Windows host, `ssh` into the VM; `sudo` needs a
password, so either use `sudo` yourself or run a throwaway privileged container:

```bash
docker run --rm --privileged -v /:/host alpine:3 \
  sh -c 'mkdir -p /host/etc/docker && install -m 644 /host/tmp/daemon.json /host/etc/docker/daemon.json'
```

| File | Destination | Applies with | Why |
|---|---|---|---|
| `docker/daemon.json` | `/etc/docker/daemon.json` | `systemctl restart docker` (restarts every container) | journald log driver: container logs survive container recreation, which json-file logs do not |
| `systemd/docker.service.d/timeout.conf` | `/etc/systemd/system/docker.service.d/timeout.conf` | `systemctl daemon-reload` | the stack needs up to ~210 s to stop; systemd's default 90 s always SIGKILLed it mid-save |
| `systemd/journald.conf.d/10-containers.conf` | `/etc/systemd/journald.conf.d/` | `systemctl restart systemd-journald` | 4 GiB / 1 week of container logs, rate limiting off (Paper would be truncated) |

After the docker daemon switch, verify with:

```bash
docker info --format '{{.LoggingDriver}}'                  # journald
docker logs misplease-proxy --tail 5                        # still works
journalctl CONTAINER_NAME=misplease-proxy --since "-10 min" | tail -5
journalctl --disk-usage
```
