"""
Standalone WSSWarp tunnel: WebSocket (binary) <-> raw TCP to Minecraft.

Run from the tunnel directory after creating a venv (see run.cmd / run.sh).
"""
from __future__ import annotations

import asyncio
import logging
import os
import socket
import threading
from http import HTTPStatus
from typing import Any
from urllib.parse import parse_qs

import websockets
from websockets.exceptions import ConnectionClosed
from websockets.server import ServerConnection

# --- constants (match Fabric WSSWarpConstants where applicable) ---
WS_BIND_HOST = "127.0.0.1"
WS_PORT = 8080
WS_PATH = "/"
BACKEND_HOST = "127.0.0.1"
BACKEND_PORT = 25565
SHARED_SECRET = "SharedSecret"
SECRET_HEADER = "X-WSSWarp-Secret"
QUERY_SECRET_PARAM = "wsswarp_secret"
TCP_READ_CHUNK = 65536
CONFIG_FILE_NAME = "wss.yml"

DEFAULT_CONFIG = {
    "ws_port": WS_PORT,
    "backend_host": BACKEND_HOST,
    "backend_port": BACKEND_PORT,
    "shared_secret": SHARED_SECRET,
    "tcp_read_chunk": TCP_READ_CHUNK,
}

log = logging.getLogger("wsswarp-tunnel")
mcdr_server = None
loop_thread: threading.Thread | None = None
loop_obj: asyncio.AbstractEventLoop | None = None
stop_event: asyncio.Event | None = None


def _info(msg: str, *args: Any) -> None:
    if mcdr_server is not None:
        mcdr_server.logger.info(msg, *args)
    else:
        logging.info(msg, *args)


def _yaml_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def _write_default_config(config_path: str) -> None:
    with open(config_path, "w", encoding="utf8") as file_handler:
        file_handler.write(f"ws_port: {DEFAULT_CONFIG['ws_port']}\n")
        file_handler.write(f"backend_host: {_yaml_quote(str(DEFAULT_CONFIG['backend_host']))}\n")
        file_handler.write(f"backend_port: {DEFAULT_CONFIG['backend_port']}\n")
        file_handler.write(f"shared_secret: {_yaml_quote(str(DEFAULT_CONFIG['shared_secret']))}\n")
        file_handler.write(f"tcp_read_chunk: {DEFAULT_CONFIG['tcp_read_chunk']}\n")


def _parse_yaml_value(raw_value: str) -> str | int:
    value = raw_value.strip()
    if value.startswith('"') and value.endswith('"') and len(value) >= 2:
        return value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    if value.startswith("'") and value.endswith("'") and len(value) >= 2:
        return value[1:-1]
    try:
        return int(value)
    except ValueError:
        return value


def _read_config(config_path: str) -> dict[str, str | int]:
    loaded: dict[str, str | int] = {}
    with open(config_path, "r", encoding="utf8") as file_handler:
        for line in file_handler:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if ":" not in stripped:
                continue
            key, raw_value = stripped.split(":", 1)
            loaded[key.strip()] = _parse_yaml_value(raw_value.split("#", 1)[0])
    return loaded


def _load_or_create_config(server) -> None:
    global WS_PORT, BACKEND_HOST, BACKEND_PORT, SHARED_SECRET, TCP_READ_CHUNK

    data_folder = server.get_data_folder()
    os.makedirs(data_folder, exist_ok=True)
    config_path = os.path.join(data_folder, CONFIG_FILE_NAME)

    if not os.path.isfile(config_path):
        _write_default_config(config_path)
        server.logger.info("Created default config at %s", config_path)
        loaded = DEFAULT_CONFIG.copy()
    else:
        loaded = DEFAULT_CONFIG.copy()
        loaded.update(_read_config(config_path))
        server.logger.info("Loaded config from %s", config_path)

    try:
        WS_PORT = int(loaded["ws_port"])
        BACKEND_HOST = str(loaded["backend_host"])
        BACKEND_PORT = int(loaded["backend_port"])
        SHARED_SECRET = str(loaded["shared_secret"])
        TCP_READ_CHUNK = int(loaded["tcp_read_chunk"])
    except (TypeError, ValueError) as e:
        server.logger.warning("Invalid config value detected, using defaults: %s", e)
        WS_PORT = int(DEFAULT_CONFIG["ws_port"])
        BACKEND_HOST = str(DEFAULT_CONFIG["backend_host"])
        BACKEND_PORT = int(DEFAULT_CONFIG["backend_port"])
        SHARED_SECRET = str(DEFAULT_CONFIG["shared_secret"])
        TCP_READ_CHUNK = int(DEFAULT_CONFIG["tcp_read_chunk"])


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
        _info("Rejecting handshake: bad path %r", path)
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
        _info("WebSocket side closed: code=%s reason=%r", e.code, e.reason)


async def _pump_tcp_to_ws(reader: asyncio.StreamReader, websocket: Any, counters: dict[str, int]) -> None:
    try:
        while True:
            data = await reader.read(TCP_READ_CHUNK)
            if not data:
                _info("Backend TCP read EOF")
                break
            await websocket.send(data)
            counters["tcp_to_ws"] += len(data)
    except ConnectionClosed:
        _info("WebSocket closed while sending from backend")


async def handler(websocket: Any) -> None:
    remote = websocket.remote_address
    _info("WebSocket tunnel session started from %s", remote)

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

    _info("Backend TCP connected to %s:%s", BACKEND_HOST, BACKEND_PORT)

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

    _info(
        "Tunnel session ended (from %s); bytes ws->tcp=%d tcp->ws=%d",
        remote,
        counters["ws_to_tcp"],
        counters["tcp_to_ws"],
    )


async def main() -> None:
    global stop_event
    stop_event = asyncio.Event()
    _info(
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
        await stop_event.wait()

def _run_loop_forever() -> None:
    global loop_obj
    loop_obj = asyncio.new_event_loop()
    asyncio.set_event_loop(loop_obj)
    try:
        loop_obj.run_until_complete(main())
    except Exception:
        if mcdr_server is not None:
            mcdr_server.logger.exception("WSSWarp tunnel loop crashed")
        else:
            log.exception("WSSWarp tunnel loop crashed")
    finally:
        pending = asyncio.all_tasks(loop_obj)
        for task in pending:
            task.cancel()
        if pending:
            loop_obj.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        loop_obj.run_until_complete(loop_obj.shutdown_asyncgens())
        loop_obj.close()
        loop_obj = None


def on_load(server, old):
    global mcdr_server, loop_thread
    mcdr_server = server
    server.logger.info("WSSWarp plugin loading")
    _load_or_create_config(server)

    if loop_thread is not None and loop_thread.is_alive():
        server.logger.warning("WSSWarp tunnel is already running")
        return

    loop_thread = threading.Thread(target=_run_loop_forever, name="wsswarp-tunnel", daemon=True)
    loop_thread.start()


def on_unload(server):
    global loop_thread, stop_event
    server.logger.info("WSSWarp plugin unloading")

    if loop_obj is not None and stop_event is not None:
        loop_obj.call_soon_threadsafe(stop_event.set)

    if loop_thread is not None and loop_thread.is_alive():
        loop_thread.join(timeout=5.0)
        if loop_thread.is_alive():
            server.logger.warning("WSSWarp tunnel thread did not exit within timeout")
        else:
            server.logger.info("WSSWarp tunnel stopped")
    loop_thread = None
    stop_event = None
