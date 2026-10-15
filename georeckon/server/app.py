"""
GeoReckon Flask-SocketIO Server Application v4.0
Implements real-time WebSocket transport layer mapping inbound SocketIO events
to the GeoReckonEngine pure-Python state machine.
"""

import os
import sys
import time
import logging
from typing import Dict, Any, Optional

from flask import Flask, request
from flask_socketio import SocketIO, emit, disconnect

from georeckon.server.models import (
    CalibrateRequest,
    SensorPacket,
    GPSRestoredPacket,
    ResetPacket,
    CalibrateDoneResponse,
    PositionPayload,
    ResetDoneResponse,
    ErrorResponse,
)
from georeckon.server.engine_wrapper import GeoReckonEngine, GeoReckonError

# Configure logging
logging.basicConfig(level=logging.INFO, format="[%(asctime)s] [%(levelname)s] %(message)s")
logger = logging.getLogger("GeoReckonServer")

app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "georeckon_secret_key_2026")

# Initialize SocketIO with cross-origin support
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# Session registry mapping SocketIO request.sid -> GeoReckonEngine instance
sessions: Dict[str, GeoReckonEngine] = {}


def get_or_create_engine(sid: str) -> GeoReckonEngine:
    """Retrieves or instantiates a GeoReckonEngine for the given connection ID."""
    if sid not in sessions:
        sessions[sid] = GeoReckonEngine()
        logger.info(f"Initialized new GeoReckonEngine session for sid='{sid}'")
    return sessions[sid]


def emit_error(sid: str, session_id: str, seq: int, t_ms: int, code: str, message: str, latency_ms: float = 0.0):
    """Helper to emit formatted error event adhering to PROTOCOL.md schema."""
    err_resp = ErrorResponse(
        session_id=session_id or "unknown",
        seq=seq if isinstance(seq, int) else 0,
        t_ms=t_ms if isinstance(t_ms, int) else 0,
        code=code,
        message=message,
        latency_ms=round(latency_ms, 2),
    )
    emit("error", err_resp.to_dict())


# ---- SocketIO Event Handlers ----

@socketio.on("connect")
def handle_connect(auth: Optional[Dict[str, Any]] = None):
    sid = request.sid
    auth_header = auth or request.headers.get("auth") or request.args.get("auth")
    logger.info(f"Client connected: sid='{sid}', auth='{auth_header}'")
    get_or_create_engine(sid)


@socketio.on("disconnect")
def handle_disconnect():
    sid = request.sid
    if sid in sessions:
        del sessions[sid]
        logger.info(f"Client disconnected & state cleaned: sid='{sid}'")


@socketio.on("ping")
def handle_ping(data: Optional[Dict[str, Any]] = None):
    t_ms = data.get("t_ms", 0) if isinstance(data, dict) else 0
    emit("pong", {"t_ms": t_ms, "server_t_ms": int(time.time() * 1000)})


@socketio.on("calibrate")
def handle_calibrate(data: Any):
    t_start = time.perf_counter()
    sid = request.sid
    engine = get_or_create_engine(sid)

    if not isinstance(data, dict):
        emit_error(sid, "", 0, 0, "INVALID_SCHEMA", "Payload must be a JSON object")
        return

    session_id = data.get("session_id", "")
    seq = data.get("seq", 0)
    t_ms = data.get("t_ms", 0)

    try:
        req = CalibrateRequest.from_dict(data)
    except ValueError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        err_msg = str(e)
        code = "NAN_OR_INF" if "non-finite" in err_msg else "INVALID_SCHEMA"
        emit_error(sid, session_id, seq, t_ms, code, err_msg, latency_ms)
        return

    try:
        res = engine.handle_calibrate(req)
        emit("calibrate_done", res.to_dict())
    except GeoReckonError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, e.code, e.message, latency_ms)
    except Exception as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        logger.exception("Unexpected exception in handle_calibrate")
        emit_error(sid, session_id, seq, t_ms, "INTERNAL_ERROR", f"Internal engine error: {e}", latency_ms)


@socketio.on("sensor")
def handle_sensor(data: Any):
    t_start = time.perf_counter()
    sid = request.sid
    engine = get_or_create_engine(sid)

    if not isinstance(data, dict):
        emit_error(sid, "", 0, 0, "INVALID_SCHEMA", "Payload must be a JSON object")
        return

    session_id = data.get("session_id", "")
    seq = data.get("seq", 0)
    t_ms = data.get("t_ms", 0)

    try:
        pkt = SensorPacket.from_dict(data)
    except ValueError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        err_msg = str(e)
        code = "NAN_OR_INF" if "non-finite" in err_msg else "INVALID_SCHEMA"
        emit_error(sid, session_id, seq, t_ms, code, err_msg, latency_ms)
        return

    try:
        pos = engine.handle_sensor(pkt)
        emit("position", pos.to_dict())
    except GeoReckonError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, e.code, e.message, latency_ms)
    except Exception as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        logger.exception("Unexpected exception in handle_sensor")
        emit_error(sid, session_id, seq, t_ms, "INTERNAL_ERROR", f"Internal engine error: {e}", latency_ms)


@socketio.on("gps_restored")
def handle_gps_restored(data: Any):
    t_start = time.perf_counter()
    sid = request.sid
    engine = get_or_create_engine(sid)

    if not isinstance(data, dict):
        emit_error(sid, "", 0, 0, "INVALID_SCHEMA", "Payload must be a JSON object")
        return

    session_id = data.get("session_id", "")
    seq = data.get("seq", 0)
    t_ms = data.get("t_ms", 0)

    try:
        pkt = GPSRestoredPacket.from_dict(data)
    except ValueError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, "INVALID_SCHEMA", str(e), latency_ms)
        return

    try:
        pos = engine.handle_gps_restored(pkt)
        emit("position", pos.to_dict())
    except GeoReckonError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, e.code, e.message, latency_ms)
    except Exception as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        logger.exception("Unexpected exception in handle_gps_restored")
        emit_error(sid, session_id, seq, t_ms, "INTERNAL_ERROR", f"Internal engine error: {e}", latency_ms)


@socketio.on("reset")
def handle_reset(data: Any):
    t_start = time.perf_counter()
    sid = request.sid
    engine = get_or_create_engine(sid)

    if not isinstance(data, dict):
        emit_error(sid, "", 0, 0, "INVALID_SCHEMA", "Payload must be a JSON object")
        return

    session_id = data.get("session_id", "")
    seq = data.get("seq", 0)
    t_ms = data.get("t_ms", 0)

    try:
        pkt = ResetPacket.from_dict(data)
    except ValueError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, "INVALID_SCHEMA", str(e), latency_ms)
        return

    try:
        res = engine.handle_reset(pkt)
        emit("reset_done", res.to_dict())
    except GeoReckonError as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        emit_error(sid, session_id, seq, t_ms, e.code, e.message, latency_ms)
    except Exception as e:
        latency_ms = (time.perf_counter() - t_start) * 1000.0
        logger.exception("Unexpected exception in handle_reset")
        emit_error(sid, session_id, seq, t_ms, "INTERNAL_ERROR", f"Internal engine error: {e}", latency_ms)


if __name__ == "__main__":
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", 5000))
    logger.info(f"Starting GeoReckon Flask-SocketIO Server on {host}:{port}...")
    socketio.run(app, host=host, port=port, debug=False, allow_unsafe_werkzeug=True)
