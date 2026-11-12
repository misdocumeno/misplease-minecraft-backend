#!/usr/bin/env python3
"""Minimal Minecraft RCON client (stdlib only)."""

from __future__ import annotations

import struct
import socket


TYPE_RESPONSE = 0
TYPE_COMMAND = 2
TYPE_LOGIN = 3


class RconError(RuntimeError):
    pass


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise RconError("connection closed")
        buf += chunk
    return buf


def _send_packet(sock: socket.socket, req_id: int, pkt_type: int, payload: str) -> None:
    body = struct.pack("<ii", req_id, pkt_type) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    (length,) = struct.unpack("<i", _recv_exact(sock, 4))
    if length < 10:
        raise RconError(f"short rcon packet length={length}")
    data = _recv_exact(sock, length)
    req_id, pkt_type = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", errors="replace")
    return req_id, pkt_type, payload


def rcon_command(
    host: str,
    password: str,
    command: str,
    *,
    port: int = 25575,
    timeout_s: float = 10.0,
) -> str:
    """Login and run one command. Returns response body (may be empty)."""
    with socket.create_connection((host, port), timeout=timeout_s) as sock:
        sock.settimeout(timeout_s)
        _send_packet(sock, 1, TYPE_LOGIN, password)
        req_id, _pkt_type, _ = _read_packet(sock)
        # Failed auth: server responds with request id -1.
        if req_id == -1:
            raise RconError("rcon auth failed")
        _send_packet(sock, 2, TYPE_COMMAND, command)
        rid, _ptype, payload = _read_packet(sock)
        if rid == -1:
            raise RconError("rcon command rejected")
        return payload
