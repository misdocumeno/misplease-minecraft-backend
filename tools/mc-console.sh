#!/usr/bin/env bash
# Run a Minecraft console command without publishing RCON on the host.
#
# RCON listens inside the container network only (compose must NOT publish 25575).
# Auth uses RCON_PASSWORD from the server container env / host .env — never paste
# that value into chat. Empty password is weaker on a shared docker network
# (any sibling container can speak RCON); prefer a random .env value.
#
# Local (on the VM / docker host):
#   ./tools/mc-console.sh main "list"
#
# From your PC over SSH:
#   MC_SSH=1 ./tools/mc-console.sh main "luckperms info"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

SERVER="${1:?usage: $0 main|tech|velocity <command...>}"
shift
CMD="${*:?command text}"

case "$SERVER" in
  main) CONTAINER=misplease-main ;;
  tech) CONTAINER=misplease-tech ;;
  velocity|proxy)
    echo "Velocity has no Minecraft RCON; use docker logs / velocity commands via its stdin attach carefully" >&2
    exit 2
    ;;
  *) echo "unknown server: $SERVER" >&2; exit 2 ;;
esac

py=$(cat <<'PY'
import os, socket, struct, sys
password = os.environ.get("RCON_PASSWORD", "")
cmd = sys.argv[1]

def send(sock, req_id, req_type, body: str):
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)

def recv(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        raise RuntimeError("short length")
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise RuntimeError("short body")
        data += chunk
    req_id, req_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return req_id, req_type, body

with socket.create_connection(("127.0.0.1", 25575), timeout=5) as sock:
    send(sock, 1, 3, password)
    rid, _, _ = recv(sock)
    if rid == -1:
        sys.exit("rcon auth failed")
    send(sock, 2, 2, cmd)
    _, _, body = recv(sock)
    if body:
        print(body)
PY
)

run_via_network_namespace() {
  if [ -z "${RCON_PASSWORD:-}" ]; then
    echo "set RCON_PASSWORD in .env (compose injects it into the server container)" >&2
    exit 1
  fi
  docker run --rm --network "container:$CONTAINER" -e RCON_PASSWORD \
    python:3.12-alpine python -c "$py" "$CMD"
}

if [ "${MC_SSH:-}" = "1" ]; then
  ssh -i "$ROOT/.cursor/ssh/misplease_vm" \
    -o UserKnownHostsFile="$ROOT/.cursor/ssh/known_hosts" \
    -o StrictHostKeyChecking=accept-new \
    mis@192.168.100.200 \
    "set -a; [ -f /home/mis/misplease/.env ] && . /home/mis/misplease/.env; set +a; docker run --rm --network container:$CONTAINER -e RCON_PASSWORD python:3.12-alpine python -c $(printf '%q' "$py") $(printf '%q' "$CMD")"
else
  run_via_network_namespace
fi
