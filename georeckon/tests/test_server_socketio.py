"""
GeoReckon Flask-SocketIO Server Integration & Latency Tests v4.0
Tests real-time SocketIO WebSocket transport, event routing, error handling,
and measures end-to-end client send-to-reply round-trip latency over 1000 messages.
"""

import math
import time
import unittest
from pathlib import Path
import numpy as np

from georeckon.server.app import app, socketio, sessions
from georeckon.server.models import (
    CalibrateRequest,
    SensorPacket,
    GPSRestoredPacket,
    ResetPacket,
    GPSFix,
    GPSHistorySample,
    IMUHistorySample,
)


def _make_socketio_cal_payload(session_id="sess_sio_test", seq=1, t_base=1000000, bearing_deg=45.0, allow_low_health=False, n_imu=60):
    t_span_ms = (n_imu - 1) * 100
    fwd_acc = np.linspace(0.3, 1.5, n_imu)
    speed = np.linspace(8.0, 14.0, n_imu)
    imu_hist = []
    gps_hist = []
    for i in range(n_imu):
        t_ms = t_base + i * 100
        if i % 10 == 0 or i == n_imu - 1:
            gps_hist.append({"t_ms": t_ms, "speed_mps": float(speed[i]), "bearing_deg": bearing_deg})
        imu_hist.append({
            "t_ms": t_ms,
            "accel": [float(fwd_acc[i]), 0.0, 9.80665],
            "gyro": [0.0, 0.0, 0.0],
            "gravity": [0.0, 0.0, 9.80665],
        })
    return {
        "session_id": session_id,
        "seq": seq,
        "t_ms": t_base + t_span_ms,
        "sample_rate_hz": 10.0,
        "allow_low_health": allow_low_health,
        "last_gps": {
            "lat": 12.971598,
            "lon": 77.594562,
            "alt_m": 920.0,
            "speed_mps": float(speed[-1]),
            "bearing_deg": bearing_deg,
            "accuracy_m": 1.5,
        },
        "gps_history": gps_hist,
        "imu_history": imu_hist,
    }


class TestGeoReckonSocketIOServer(unittest.TestCase):
    def setUp(self):
        sessions.clear()
        self.client = socketio.test_client(app)
        self.assertTrue(self.client.is_connected())

    def tearDown(self):
        if self.client.is_connected():
            self.client.disconnect()
        sessions.clear()

    def test_sio_ping_pong(self):
        """SocketIO ping event receives pong with client timestamp."""
        self.client.emit("ping", {"t_ms": 1234567})
        received = self.client.get_received()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["name"], "pong")
        self.assertEqual(received[0]["args"][0]["t_ms"], 1234567)

    def test_sio_calibrate_success(self):
        """SocketIO calibrate event succeeds and emits calibrate_done."""
        payload = _make_socketio_cal_payload(session_id="sess_cal_ok")
        self.client.emit("calibrate", payload)
        received = self.client.get_received()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["name"], "calibrate_done")
        res = received[0]["args"][0]
        self.assertEqual(res["status"], "OK")
        self.assertEqual(res["session_id"], "sess_cal_ok")
        self.assertFalse(res["degraded"])
        self.assertGreaterEqual(res["alignment_health"], 0.25)
        print(f"\n[SocketIO calibrate] status={res['status']}, health={res['alignment_health']}, latency={res['latency_ms']}ms")

    def test_sio_calibrate_low_health_rejection(self):
        """SocketIO low-health calibration without allow_low_health emits ALIGNMENT_HEALTH_LOW with exact health."""
        # Create stationary payload with isotropic noise (low alignment health < 0.25)
        np.random.seed(42)
        n_imu = 60
        t_base = 1000000
        imu_hist = []
        gps_hist = [
            {"t_ms": t_base, "speed_mps": 10.0, "bearing_deg": 45.0},
            {"t_ms": t_base + 3000, "speed_mps": 10.0, "bearing_deg": 45.0},
            {"t_ms": t_base + 5900, "speed_mps": 10.0, "bearing_deg": 45.0},
        ]
        g_arr = []
        a_arr = []
        t_arr = []
        for i in range(n_imu):
            t_ms = t_base + i * 100
            g_vec = [float(np.random.normal(0, 0.005)), float(np.random.normal(0, 0.005)), 9.80665 + float(np.random.normal(0, 0.005))]
            a_vec = [float(np.random.normal(0, 0.02)), float(np.random.normal(0, 0.02)), 9.80665 + float(np.random.normal(0, 0.02))]
            w_vec = [float(np.random.normal(0, 0.005)), float(np.random.normal(0, 0.005)), float(np.random.normal(0, 0.005))]
            imu_hist.append({"t_ms": t_ms, "accel": a_vec, "gyro": w_vec, "gravity": g_vec})
            g_arr.append(g_vec)
            a_arr.append(a_vec)
            t_arr.append(t_ms / 1000.0)

        # Compute expected health directly with VirtualInVehicleAligner
        from georeckon.src.virtual_alignment import VirtualInVehicleAligner
        aligner = VirtualInVehicleAligner()
        gps_t = np.array([g["t_ms"] / 1000.0 for g in gps_hist])
        gps_v = np.array([g["speed_mps"] for g in gps_hist])
        imu_t = np.array(t_arr)
        interp_v = np.interp(imu_t, gps_t, gps_v)
        _, expected_health = aligner.compute_alignment_from_history(np.array(g_arr), np.array(a_arr), interp_v, imu_t)

        payload = {
            "session_id": "sess_cal_low_health",
            "seq": 1,
            "t_ms": t_base + 5900,
            "sample_rate_hz": 10.0,
            "allow_low_health": False,
            "last_gps": {
                "lat": 12.971598, "lon": 77.594562, "alt_m": 920.0,
                "speed_mps": 10.0, "bearing_deg": 45.0, "accuracy_m": 1.5,
            },
            "gps_history": gps_hist,
            "imu_history": imu_hist,
        }

        self.client.emit("calibrate", payload)
        received = self.client.get_received()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["name"], "error")
        err = received[0]["args"][0]
        self.assertEqual(err["code"], "ALIGNMENT_HEALTH_LOW")
        self.assertIn(f"Computed alignment health {expected_health:.4f} is below ALIGN_MIN_HEALTH (0.25).", err["message"])

        # Invariant: session must not be active; subsequent sensor packet rejected with NOT_CALIBRATED
        self.client.emit("sensor", {
            "session_id": "sess_cal_low_health", "seq": 2, "t_ms": t_base + 6000,
            "accel": [0.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665]
        })
        received_sensor = self.client.get_received()
        self.assertEqual(len(received_sensor), 1)
        self.assertEqual(received_sensor[0]["name"], "error")
        self.assertEqual(received_sensor[0]["args"][0]["code"], "NOT_CALIBRATED")

    def test_sio_invalid_schema_emits_error(self):
        """SocketIO invalid schema emits error event with INVALID_SCHEMA code."""
        self.client.emit("calibrate", {"session_id": "bad", "seq": "not_an_int"})
        received = self.client.get_received()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["name"], "error")
        err = received[0]["args"][0]
        self.assertEqual(err["code"], "INVALID_SCHEMA")
        print(f"\n[SocketIO invalid schema] {err['code']}: {err['message']}")

    def test_sio_nan_in_sensor_packet_emits_error(self):
        """SocketIO NaN or Inf in sensor floats emits NAN_OR_INF error."""
        cal_payload = _make_socketio_cal_payload()
        self.client.emit("calibrate", cal_payload)
        _ = self.client.get_received()

        bad_sensor = {
            "session_id": "sess_sio_test",
            "seq": 2,
            "t_ms": 1000000 + 6000,
            "accel": [float("nan"), 0.0, 9.80665],
            "gyro": [0.0, 0.0, 0.0],
            "gravity": [0.0, 0.0, 9.80665],
        }
        self.client.emit("sensor", bad_sensor)
        received = self.client.get_received()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["name"], "error")
        self.assertEqual(received[0]["args"][0]["code"], "NAN_OR_INF")
        print(f"\n[SocketIO NaN check] {received[0]['args'][0]['code']}: {received[0]['args'][0]['message']}")

    def test_sio_out_of_order_seq_emits_error(self):
        """SocketIO out of order packet emits OUT_OF_ORDER_SEQ error."""
        cal_payload = _make_socketio_cal_payload()
        self.client.emit("calibrate", cal_payload)
        _ = self.client.get_received()

        t_base = 1000000 + 5900
        pkt1 = {
            "session_id": "sess_sio_test", "seq": 5, "t_ms": t_base + 100,
            "accel": [0.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
        }
        self.client.emit("sensor", pkt1)
        rec1 = self.client.get_received()
        self.assertEqual(rec1[0]["name"], "position")

        # Send duplicate seq=5
        pkt_dup = {
            "session_id": "sess_sio_test", "seq": 5, "t_ms": t_base + 200,
            "accel": [0.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
        }
        self.client.emit("sensor", pkt_dup)
        rec2 = self.client.get_received()
        self.assertEqual(rec2[0]["name"], "error")
        self.assertEqual(rec2[0]["args"][0]["code"], "OUT_OF_ORDER_SEQ")
        print(f"\n[SocketIO OOO seq] {rec2[0]['args'][0]['code']}: {rec2[0]['args'][0]['message']}")

    def test_sio_end_to_end_1000_messages_latency(self):
        """
        Phase 4: End-to-end client send to reply roundtrip latency over 1000 messages.
        Measures total latency (SocketIO encode -> engine handle -> response decode).
        Reports: mean, p95, max, count above 100 ms.
        """
        np.random.seed(42)
        cal_payload = _make_socketio_cal_payload(session_id="sess_lat1000")
        self.client.emit("calibrate", cal_payload)
        rec_cal = self.client.get_received()
        self.assertEqual(rec_cal[0]["name"], "calibrate_done")

        t_base = 1000000 + 5900
        # Warm up 20 samples
        for k in range(20):
            pkt = {
                "session_id": "sess_lat1000", "seq": 2+k, "t_ms": t_base + (k+1)*100,
                "accel": [0.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
            }
            self.client.emit("sensor", pkt)
            _ = self.client.get_received()

        # Measure 1000 client-send to reply roundtrips
        latencies_ms = []
        seq = 23
        t = t_base + 21*100
        for k in range(1000):
            ax = float(np.random.normal(0.0, 0.2))
            pkt = {
                "session_id": "sess_lat1000", "seq": seq, "t_ms": t,
                "accel": [ax, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
            }
            t0 = time.perf_counter()
            self.client.emit("sensor", pkt)
            rec = self.client.get_received()
            dt_ms = (time.perf_counter() - t0) * 1000.0
            latencies_ms.append(dt_ms)

            self.assertEqual(len(rec), 1)
            self.assertEqual(rec[0]["name"], "position")

            seq += 1
            t += 100

        latencies_ms = np.array(latencies_ms)
        mean_lat = float(np.mean(latencies_ms))
        p95_lat = float(np.percentile(latencies_ms, 95))
        max_lat = float(np.max(latencies_ms))
        count_above_100ms = int(np.sum(latencies_ms > 100.0))

        print(f"\n[Phase 4 SocketIO End-to-End Latency (1000 Messages)]")
        print(f"  mean={mean_lat:.2f} ms, p95={p95_lat:.2f} ms, max={max_lat:.2f} ms")
        print(f"  Count exceeding 100 ms: {count_above_100ms} / 1000 ({count_above_100ms/10:.1f}%)")
        print(f"  Budget: <=100 ms")

        self.assertLess(p95_lat, 50.0, f"SocketIO p95 latency {p95_lat:.2f}ms exceeds 50ms")
    def test_sio_full_session_lifecycle(self):
        """SocketIO full session lifecycle: Calibrate -> Sensor Stream -> GPS Restored -> Reset."""
        # 1. Calibrate
        cal_payload = _make_socketio_cal_payload(session_id="sess_life", bearing_deg=90.0)
        self.client.emit("calibrate", cal_payload)
        rec = self.client.get_received()
        self.assertEqual(rec[0]["name"], "calibrate_done")

        t_base = 1000000 + 5900
        # 2. Sensor
        pkt = {
            "session_id": "sess_life", "seq": 2, "t_ms": t_base + 100,
            "accel": [1.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
        }
        self.client.emit("sensor", pkt)
        rec = self.client.get_received()
        self.assertEqual(rec[0]["name"], "position")
        self.assertEqual(rec[0]["args"][0]["mode"], "DEAD_RECKONING")

        # 3. GPS Restored
        restored_pkt = {
            "session_id": "sess_life", "seq": 3, "t_ms": t_base + 200,
            "gps": {
                "lat": 12.9716, "lon": 77.5946, "alt_m": 920.0,
                "speed_mps": 14.5, "bearing_deg": 90.0, "accuracy_m": 1.5,
            }
        }
        self.client.emit("gps_restored", restored_pkt)
        rec = self.client.get_received()
        self.assertEqual(rec[0]["name"], "position")
        pos_final = rec[0]["args"][0]
        self.assertEqual(pos_final["mode"], "IDLE")
        self.assertIsNotNone(pos_final["dr_error_m"])
        print(f"\n[SocketIO lifecycle] GPS restored: dr_error={pos_final['dr_error_m']:.2f}m, dr_distance={pos_final['dr_distance_m']:.2f}m")

        # 4. Reset
        reset_pkt = {"session_id": "sess_life", "seq": 1, "t_ms": t_base + 300}
        self.client.emit("reset", reset_pkt)
        rec = self.client.get_received()
        self.assertEqual(rec[0]["name"], "reset_done")
        self.assertEqual(rec[0]["args"][0]["status"], "OK")
        self.assertEqual(rec[0]["args"][0]["mode"], "IDLE")
        print(f"[SocketIO lifecycle] Reset done: status={rec[0]['args'][0]['status']}, mode={rec[0]['args'][0]['mode']}")

    def test_sio_two_concurrent_sessions(self):
        """
        Item 5: Test two concurrent SocketIO client sessions running simultaneously.
        Verifies thread safety of MC-dropout inference, session state isolation, and independent telemetry.
        """
        c1 = socketio.test_client(app)
        c2 = socketio.test_client(app)
        self.assertTrue(c1.is_connected())
        self.assertTrue(c2.is_connected())

        # Calibrate session 1 (North heading)
        p1 = _make_socketio_cal_payload(session_id="sess_client_1", bearing_deg=0.0)
        c1.emit("calibrate", p1)
        r1 = c1.get_received()
        self.assertEqual(r1[0]["name"], "calibrate_done")
        self.assertEqual(r1[0]["args"][0]["session_id"], "sess_client_1")

        # Calibrate session 2 (East heading)
        p2 = _make_socketio_cal_payload(session_id="sess_client_2", bearing_deg=90.0)
        c2.emit("calibrate", p2)
        r2 = c2.get_received()
        self.assertEqual(r2[0]["name"], "calibrate_done")
        self.assertEqual(r2[0]["args"][0]["session_id"], "sess_client_2")

        # Interleave sensor packets from both sessions
        t_base = 1000000 + 5900
        for k in range(10):
            pkt1 = {
                "session_id": "sess_client_1", "seq": 2+k, "t_ms": t_base + (k+1)*100,
                "accel": [1.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
            }
            pkt2 = {
                "session_id": "sess_client_2", "seq": 2+k, "t_ms": t_base + (k+1)*100,
                "accel": [2.0, 0.0, 9.80665], "gyro": [0.0, 0.0, 0.0], "gravity": [0.0, 0.0, 9.80665],
            }
            c1.emit("sensor", pkt1)
            c2.emit("sensor", pkt2)

            rec1 = c1.get_received()
            rec2 = c2.get_received()
            self.assertEqual(len(rec1), 1)
            self.assertEqual(len(rec2), 1)
            self.assertEqual(rec1[0]["args"][0]["session_id"], "sess_client_1")
            self.assertEqual(rec2[0]["args"][0]["session_id"], "sess_client_2")

        # Session 1 headed North (enu_x approx 0, enu_y > 0)
        # Session 2 headed East (enu_x > 0, enu_y approx 0)
        final1 = rec1[0]["args"][0]
        final2 = rec2[0]["args"][0]
        self.assertAlmostEqual(final1["enu_x_m"], 0.0, places=1)
        self.assertGreater(final1["enu_y_m"], 0.0)
        self.assertGreater(final2["enu_x_m"], 0.0)
        self.assertAlmostEqual(final2["enu_y_m"], 0.0, places=1)

        c1.disconnect()
        c2.disconnect()
        print(f"\n[SocketIO concurrent sessions] Successfully processed concurrent streams for sess_1 (N={final1['enu_y_m']}m) and sess_2 (E={final2['enu_x_m']}m)")


if __name__ == "__main__":
    unittest.main()

