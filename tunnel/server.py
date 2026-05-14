"""
Standalone WSSWarp tunnel: WebSocket (binary) <-> raw TCP to Minecraft.

Run from the tunnel directory after creating a venv (see run.cmd / run.sh).
"""
from __future__ import annotations

import asyncio
import logging
import socket
import sys
from http import HTTPStatus
from typing import Any
from urllib.parse import parse_qs

import websockets
from websockets.exceptions import ConnectionClosed
from websockets.server import ServerConnection

# --- constants (match Fabric WSSWarpConstants where applicable) ---
WS_BIND_HOST = "127.0.0.1"
WS_PORT = 8080
WS_PATH = "/mc"
BACKEND_HOST = "127.0.0.1"
BACKEND_PORT = 25565
SHARED_SECRET = "SharedSecret"
SECRET_HEADER = "X-WSSWarp-Secret"
QUERY_SECRET_PARAM = "wsswarp_secret"
TCP_READ_CHUNK = 65536

log = logging.getLogger("wsswarp-tunnel")


def _path_only(path: str) -> str:
    return path.split("?", 1)[0] if path else ""


def _secret_ok(request_headers: Any, request_path: str) -> bool:
    if request_headers.get(SECRET_HEADER) == SHARED_SECRET:
        return True
    if "?" not in request_path:
        return False
    query = request_path.split("?", 1)[1]
    vals = parse_qs(query).get(QUERY_SECRET_PARAM, [])
    return len(vals) == 1 and vals[0] == SHARED_SECRET


def process_request(connection: ServerConnection, request: Any) -> Any:
    path = getattr(request, "path", "") or ""
    if _path_only(path) != WS_PATH:
        log.info("Rejecting handshake: bad path %r", path)
        return connection.respond(HTTPStatus.NOT_FOUND, "Not Found\n")
    if not _secret_ok(request.headers, path):
        log.warning(
            "Rejecting handshake: invalid or missing secret from %s",
            connection.remote_address,
        )
        return connection.respond(HTTPStatus.FORBIDDEN, "Forbidden\n")
    return None


async def _pump_ws_to_tcp(websocket: Any, writer: asyncio.StreamWriter, counters: dict[str, int]) -> None:
    try:
        async for message in websocket:
            if isinstance(message, bytes):
                writer.write(message)
                await writer.drain()
                counters["ws_to_tcp"] += len(message)
            else:
                log.warning("Ignoring non-binary WebSocket message from client")
    except ConnectionClosed as e:
        log.info("WebSocket side closed: code=%s reason=%r", e.code, e.reason)


async def _pump_tcp_to_ws(reader: asyncio.StreamReader, websocket: Any, counters: dict[str, int]) -> None:
    try:
        while True:
            data = await reader.read(TCP_READ_CHUNK)
            if not data:
                log.info("Backend TCP read EOF")
                break
            await websocket.send(data)
            counters["tcp_to_ws"] += len(data)
    except ConnectionClosed:
        log.info("WebSocket closed while sending from backend")


async def handler(websocket: Any) -> None:
    remote = websocket.remote_address
    log.info("WebSocket tunnel session started from %s", remote)

    try:
        reader, writer = await asyncio.open_connection(BACKEND_HOST, BACKEND_PORT)
    except OSError as e:
        log.error("Backend connect failed %s:%s: %s", BACKEND_HOST, BACKEND_PORT, e)
        await websocket.close(code=1011, reason="backend unreachable")
        return

    sock = writer.transport.get_extra_info("socket")
    if sock is not None and hasattr(socket, "TCP_NODELAY"):
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as e:
            log.warning("Could not set TCP_NODELAY on backend socket: %s", e)

    log.info("Backend TCP connected to %s:%s", BACKEND_HOST, BACKEND_PORT)

    counters = {"ws_to_tcp": 0, "tcp_to_ws": 0}
    ws_task = asyncio.create_task(_pump_ws_to_tcp(websocket, writer, counters), name="ws_to_tcp")
    tcp_task = asyncio.create_task(_pump_tcp_to_ws(reader, websocket, counters), name="tcp_to_ws")

    _done, pending = await asyncio.wait(
        (ws_task, tcp_task),
        return_when=asyncio.FIRST_COMPLETED,
    )
    for t in pending:
        t.cancel()
    await asyncio.gather(*pending, return_exceptions=True)

    for t in _done:
        if t.cancelled():
            continue
        exc = t.exception()
        if exc is not None:
            log.error("Pump task ended with error: %s", exc)

    try:
        writer.close()
        await writer.wait_closed()
    except Exception as e:
        log.debug("Backend writer close: %s", e)

    try:
        await websocket.close()
    except Exception as e:
        log.debug("WebSocket close: %s", e)

    log.info(
        "Tunnel session ended (from %s); bytes ws->tcp=%d tcp->ws=%d",
        remote,
        counters["ws_to_tcp"],
        counters["tcp_to_ws"],
    )


async def main() -> None:
    log.info(
        "WSSWarp tunnel listening ws://%s:%s%s -> tcp://%s:%s",
        WS_BIND_HOST,
        WS_PORT,
        WS_PATH,
        BACKEND_HOST,
        BACKEND_PORT,
    )
    async with websockets.serve(
        handler,
        WS_BIND_HOST,
        WS_PORT,
        process_request=process_request,
        max_size=None,
    ):
        await asyncio.get_running_loop().create_future()


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
        stream=sys.stderr,
    )
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("Shutting down")
