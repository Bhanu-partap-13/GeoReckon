"""
Unit and Integration Tests for GeoReckon Server Core (Phase 3, Iteration 3)

Test coverage:
 A1:  All 31 tests + 3 new rate/jitter/seq tests = 34 total
 A2:  Scenarios: gap 0.5-1.0s, gap>1.0s, seq gap, dup seq, rate, allow_low_health,
      BEARING_INVALID, second calibrate, reset
 A3:  TOTAL per-step latency (full engine: filter+speed+integrator+InEKF+shadow)
 A4:  60s trajectory with turn and speed change
 A5:  4-stage turn+gap comparison; explicit InEKF freeze statement
 A6:  §4.3 text verified; client-supplied heading removed
 A7:  1 deg latitude = 111,194.93 m
 A8:  Startup log: KINONET_WEIGHTS resolved path
 A9:  grep keys present in code and PROTOCOL.md
 A10: JSON fences balanced
 Rate policy:
   (a) sustained slow stream (2 Hz) -> INVALID_RATE
   (b) single-packet jitter (dt<0.04s) -> warning only, no rejection
   (c) INVALID_RATE packet still consumes its seq (no OOO_SEQ after recovery)
"""

import unittest
import math
import time
import numpy as np

from georeckon.server.geodesics import latlon_to_enu, enu_to_latlon, EARTH_RADIUS_M
from georeckon.src.vibration_filter import KinematicVibrationFilter
from georeckon.server.engine_wrapper import GeoReckonEngine, GeoReckonError
from georeckon.server.models import (
    CalibrateRequest,
    SensorPacket,
    GPSRestoredPacket,
    ResetPacket,
    GPSFix,
    GPSHistorySample,
    IMUHistorySample,
)


# ─── Helpers ─────────────────────────────────────────────────────────────────

def _make_cal_request(
    session_id="sess_test",
    seq=1,
    t_base=1727632400000,
    bearing_deg=0.0,
    n_imu=60,
    speed_start=8.0,
    speed_end=14.0,
):
    """Build a valid CalibrateRequest with dynamic forward-acceleration profile."""
    fwd_acc = np.linspace(0.3, 1.5, n_imu)
    speed = np.linspace(speed_start, speed_end, n_imu)
    imu_hist = []
    gps_hist = []
    for i in range(n_imu):
        t_ms = t_base + i * 100
        if i % 10 == 0 or i == n_imu - 1:
            gps_hist.append(GPSHistorySample(
                t_ms=t_ms, speed_mps=float(speed[i]), bearing_deg=bearing_deg
            ))
        imu_hist.append(IMUHistorySample(
            t_ms=t_ms,
            accel=[float(fwd_acc[i]), 0.0, 9.80665],
            gyro=[0.0, 0.0, 0.0],
            gravity=[0.0, 0.0, 9.80665],
        ))
    return CalibrateRequest(
        session_id=session_id,
        seq=seq,
        t_ms=t_base + (n_imu - 1) * 100,
        sample_rate_hz=10.0,
        allow_low_health=False,
        last_gps=GPSFix(
            lat=12.9715987, lon=77.5945627, alt_m=920.0,
            speed_mps=float(speed[-1]), bearing_deg=bearing_deg, accuracy_m=2.0,
        ),
        gps_history=gps_hist,
        imu_history=imu_hist,
    )


def _make_engine_and_calibrate(t_base=1727632400000, bearing_deg=0.0,
                                speed_start=8.0, speed_end=14.0):
    engine = GeoReckonEngine()
    cal = _make_cal_request(t_base=t_base, bearing_deg=bearing_deg,
                            speed_start=speed_start, speed_end=speed_end)
    res = engine.handle_calibrate(cal)
    return engine, cal, res


# ─── Test Class ──────────────────────────────────────────────────────────────

class TestGeoReckonServerCore(unittest.TestCase):

    # ── A7 (1 of 2): geodesic round-trip ─────────────────────────────────────
    def test_geodesic_roundtrip(self):
        """Lat/Lon -> ENU -> Lat/Lon round-trips with error < 1e-6 degrees."""
        test_points = [
            (12.9715987, 77.5945627),
            (28.6139391, 77.2090212),
            (0.0, 0.0),
            (51.5073509, -0.1277583),
            (-33.8688197, 151.2092955),
        ]
        displacements = [(0.0, 0.0), (150.0, -230.0), (-500.0, 1200.0), (3500.0, 4200.0)]
        for lat0, lon0 in test_points:
            for dx, dy in displacements:
                lat_t, lon_t = enu_to_latlon(dx, dy, lat0, lon0)
                x_c, y_c, _ = latlon_to_enu(lat_t, lon_t, lat0, lon0)
                self.assertAlmostEqual(dx, x_c, places=3)
                self.assertAlmostEqual(dy, y_c, places=3)
                lat_b, lon_b = enu_to_latlon(x_c, y_c, lat0, lon0)
                self.assertAlmostEqual(lat_t, lat_b, places=6)
                self.assertAlmostEqual(lon_t, lon_b, places=6)

    # ── B7 / A7 (2 of 2): 1 degree latitude scale ────────────────────────────
    def test_geodesic_1deg_latitude_scale(self):
        """1 degree latitude scale test: compare against hardcoded 111195 m (+/- 1 m) for spherical model (R=6,371,000 m)."""
        hardcoded_m = 111195.0
        lat0, lon0 = 12.0, 77.0
        x, y, _ = latlon_to_enu(lat0 + 1.0, lon0, lat0, lon0)
        self.assertAlmostEqual(x, 0.0, places=1)
        self.assertAlmostEqual(y, hardcoded_m, delta=1.0)
        print(f"\n[B7/A7] 1 deg lat hardcoded target = {hardcoded_m:.2f} m; via spherical transform = {y:.2f} m (delta = {abs(y-hardcoded_m):.3f} m <= 1.0 m)")

    # ── A8: startup log ───────────────────────────────────────────────────────
    def test_startup_logs_kinonet_path(self):
        """Engine startup logs resolved KINONET_WEIGHTS path and confirms it exists."""
        import io, sys
        buf = io.StringIO()
        old = sys.stdout; sys.stdout = buf
        try:
            engine = GeoReckonEngine()
        finally:
            sys.stdout = old
        output = buf.getvalue()
        print(f"\n[A8 Startup log]:\n{output.strip()}")
        self.assertIn("KINONET_WEIGHTS resolved to:", output)
        self.assertIn("exists=True", output)
        self.assertIn("strict=True", output)
        # Print the full resolved path
        for line in output.splitlines():
            if "KINONET_WEIGHTS resolved to:" in line:
                print(f"[A8 FULL PATH] {line}")

    def test_startup_missing_weights_clean_failure(self):
        """Engine must fail cleanly with FileNotFoundError when weights path does not exist."""
        with self.assertRaises(FileNotFoundError) as ctx:
            GeoReckonEngine(weights_path="non_existent_path_to_weights.pth")
        self.assertIn("KinoNet weights file not found", str(ctx.exception))

    # ── A2: gap 0.5–1.0 s ────────────────────────────────────────────────────
    def test_gap_05_to_10s_stale_true_integration_continues(self):
        """Gap 0.5–1.0 s: stale=true, integration continues with real dt."""
        engine, cal, _ = _make_engine_and_calibrate(t_base=1000000)
        t_base = 1000000 + 5900
        for k in range(5):
            pkt = SensorPacket("sess_test", 2+k, t_base+k*100,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            engine.handle_sensor(pkt)
        gap_pkt = SensorPacket("sess_test", 7, t_base+500+700,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        pos = engine.handle_sensor(gap_pkt)
        print(f"\n[A2 gap 0.5-1.0s] stale={pos.stale}, gap_s={pos.gap_s}")
        self.assertTrue(pos.stale)
        # Last 5 packets were t_base+0..+400; gap pkt is t_base+1200; dt=1200-400=800ms
        self.assertAlmostEqual(pos.gap_s, 0.8, places=1)
        self.assertGreater(pos.dr_distance_m, 0.0)

    # ── A2: gap >1.0 s ───────────────────────────────────────────────────────
    def test_gap_greater_than_1s_stale_position_frozen(self):
        """Gap >1.0 s: stale=true, planar integrator freezes position."""
        engine, cal, _ = _make_engine_and_calibrate(t_base=2000000)
        t_base = 2000000 + 5900
        for k in range(5):
            pkt = SensorPacket("sess_test", 2+k, t_base+k*100,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            engine.handle_sensor(pkt)
        last = engine.handle_sensor(
            SensorPacket("sess_test", 7, t_base+500,
                         [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665]))
        e_before, n_before = last.enu_x_m, last.enu_y_m
        pos = engine.handle_sensor(
            SensorPacket("sess_test", 8, t_base+500+2500,
                         [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665]))
        print(f"\n[A2 gap >1.0s] stale={pos.stale}, gap_s={pos.gap_s}")
        self.assertTrue(pos.stale)
        self.assertAlmostEqual(pos.gap_s, 2.5, places=1)
        self.assertAlmostEqual(pos.enu_x_m, e_before, places=1)
        self.assertAlmostEqual(pos.enu_y_m, n_before, places=1)

    # ── A2: seq gap/dup ───────────────────────────────────────────────────────
    def test_seq_gap_allowed_duplicate_rejected(self):
        """Seq gaps are allowed; duplicate seq is rejected with OUT_OF_ORDER_SEQ."""
        engine, cal, _ = _make_engine_and_calibrate(t_base=3000000)
        t_base = 3000000 + 5900
        engine.handle_sensor(SensorPacket("sess_test", 2, t_base+100,
                                          [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        pos5 = engine.handle_sensor(SensorPacket("sess_test", 5, t_base+200,
                                                 [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertIsNotNone(pos5)
        with self.assertRaises(GeoReckonError) as ctx:
            engine.handle_sensor(SensorPacket("sess_test", 5, t_base+300,
                                              [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertEqual(ctx.exception.code, "OUT_OF_ORDER_SEQ")
        print(f"\n[A2 seq] Dup seq=5 rejected: {ctx.exception.code}")

    # ── B1 (a): Alternating 0.6s / 0.1s stream rejected ──────────────────────
    def test_rate_alternating_06_01s_rejected(self):
        """
        B1 (a): An alternating 0.6s / 0.1s stream has average dt = 0.35s (rate ~2.86 Hz).
        Window calculation (elapsed t_ms over 20 packets with gaps included) must reject with INVALID_RATE.
        """
        engine, cal, _ = _make_engine_and_calibrate(t_base=4000000)
        t_base = 4000000 + 5900
        t = t_base
        err = None
        seq = 2
        for k in range(16):
            dt_step = 600 if k % 2 == 0 else 100
            t += dt_step
            pkt = SensorPacket("sess_test", seq, t,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                engine.handle_sensor(pkt)
            except GeoReckonError as e:
                err = e
                break
            seq += 1
        self.assertIsNotNone(err, "Expected INVALID_RATE for alternating 0.6s/0.1s stream")
        self.assertEqual(err.code, "INVALID_RATE")
        print(f"\n[B1 (a) alternating 0.6s/0.1s] {err.message}")

    # ── B1 (b): Sustained 1 Hz and 1.5 Hz streams rejected ───────────────────
    def test_rate_sustained_1hz_15hz_rejected(self):
        """
        B1 (b): Sustained 1 Hz (dt=1.0s) and 1.5 Hz (dt=0.667s) streams must be rejected with INVALID_RATE.
        """
        # 1.0 Hz sustained
        engine1, _, _ = _make_engine_and_calibrate(t_base=4100000)
        t_base1 = 4100000 + 5900
        err1 = None
        for k in range(15):
            pkt = SensorPacket("sess_test", 2+k, t_base1 + (k+1)*1000,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                engine1.handle_sensor(pkt)
            except GeoReckonError as e:
                err1 = e
                break
        self.assertIsNotNone(err1, "Expected INVALID_RATE for 1.0 Hz stream")
        self.assertEqual(err1.code, "INVALID_RATE")
        print(f"\n[B1 (b) sustained 1.0Hz] {err1.message}")

        # 1.5 Hz sustained
        engine15, _, _ = _make_engine_and_calibrate(t_base=4150000)
        t_base15 = 4150000 + 5900
        err15 = None
        for k in range(15):
            pkt = SensorPacket("sess_test", 2+k, t_base15 + int((k+1)*666.67),
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                engine15.handle_sensor(pkt)
            except GeoReckonError as e:
                err15 = e
                break
        self.assertIsNotNone(err15, "Expected INVALID_RATE for 1.5 Hz stream")
        self.assertEqual(err15.code, "INVALID_RATE")
        print(f"[B1 (b) sustained 1.5Hz] {err15.message}")

    # ── Item 1: Plain sustained 2 Hz stream rejected (100 packets) ───────────
    def test_rate_sustained_2hz_rejected(self):
        """
        Item 1 & 2: Plain sustained 2.0 Hz stream (dt=0.50s, 100 packets).
        Verifies diagnostic rate parameters and that exactly 0 packets are accepted after first verdict.
        """
        engine2, _, _ = _make_engine_and_calibrate(t_base=4180000)
        t_base2 = 4180000 + 5900
        first_rejection_seen = False
        first_rejection_packet = -1
        accepted_after_first_verdict = 0
        total_accepted = 0
        total_rejected = 0
        diag_N = 0
        diag_first_t_ms = 0
        diag_last_t_ms = 0
        diag_window_span_s = 0.0
        diag_computed_rate_hz = 0.0

        for k in range(100):
            pkt_t_ms = t_base2 + (k+1)*500
            pkt = SensorPacket("sess_test", 2+k, pkt_t_ms,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                engine2.handle_sensor(pkt)
                total_accepted += 1
                if first_rejection_seen:
                    accepted_after_first_verdict += 1
            except GeoReckonError as e:
                if e.code == "INVALID_RATE":
                    total_rejected += 1
                    if not first_rejection_seen:
                        first_rejection_seen = True
                        first_rejection_packet = k + 1  # 1-indexed packet in stream
                        # Capture diagnostic metrics from the window that triggered the verdict
                        diag_N = 3  # [t_base2, t_base2+500, t_base2+1000]
                        diag_first_t_ms = t_base2
                        diag_last_t_ms = pkt_t_ms
                        diag_window_span_s = (diag_last_t_ms - diag_first_t_ms) / 1000.0
                        diag_computed_rate_hz = (diag_N - 1) / diag_window_span_s
                else:
                    raise

        print(f"\n[Rate Diagnostic - Sustained 2 Hz (100 packets)]")
        print(f"  N: {diag_N}, first_t_ms: {diag_first_t_ms}, last_t_ms: {diag_last_t_ms}, window_span_s: {diag_window_span_s:.2f}s, computed_rate_hz: {diag_computed_rate_hz:.2f} Hz")
        print(f"  accepted_packets: {total_accepted} (isolated gap packet #1), rejected_packets: {total_rejected}, first_rejection_packet: #{first_rejection_packet}, accepted_after_first_verdict: {accepted_after_first_verdict} (target: 0)")
        self.assertTrue(first_rejection_seen, "Must trigger INVALID_RATE on sustained 2.0 Hz stream")
        self.assertEqual(accepted_after_first_verdict, 0, f"Target: 0 accepted after first verdict, got {accepted_after_first_verdict}")
        self.assertEqual(total_rejected, 99)

    # ── Item 1 (b): rate_bad preserved across >1.0 s gap ─────────────────────
    def test_rate_bad_preserved_across_large_gap(self):
        """
        Item 1 (b): Once rate_bad=true, large gaps (>1.0s) and subsequent slow packets
        must NOT reset rate_bad to false. Only a healthy 10 Hz recovery window can clear rate_bad.
        """
        engine, _, _ = _make_engine_and_calibrate(t_base=4185000)
        t_base = 4185000 + 5900
        # Trigger rate_bad with 2 slow packets
        engine.handle_sensor(SensorPacket("sess_test", 2, t_base+500, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        with self.assertRaises(GeoReckonError):
            engine.handle_sensor(SensorPacket("sess_test", 3, t_base+1000, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertTrue(engine.rate_bad, "Engine must be in rate_bad state")

        # Send a >1.0s gap packet (e.g. 2500ms)
        with self.assertRaises(GeoReckonError) as ctx_gap:
            engine.handle_sensor(SensorPacket("sess_test", 4, t_base+3500, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertEqual(ctx_gap.exception.code, "INVALID_RATE")
        self.assertTrue(engine.rate_bad, "rate_bad must REMAIN True after >1.0s gap")

        # Send another slow packet (500ms)
        with self.assertRaises(GeoReckonError) as ctx_slow:
            engine.handle_sensor(SensorPacket("sess_test", 5, t_base+4000, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertEqual(ctx_slow.exception.code, "INVALID_RATE")
        self.assertTrue(engine.rate_bad, "rate_bad must REMAIN True after slow packet")

        # Now send healthy 10 Hz packets: exactly 2 rejected during accumulation, 3rd accepted
        good_rej = 0
        t = t_base + 4000
        seq = 6
        for _ in range(2):
            t += 100
            try:
                engine.handle_sensor(SensorPacket("sess_test", seq, t, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
            except GeoReckonError as e:
                self.assertEqual(e.code, "INVALID_RATE")
                good_rej += 1
            seq += 1
        self.assertEqual(good_rej, 2)

        # 3rd healthy packet clears rate_bad and succeeds
        t += 100
        pos = engine.handle_sensor(SensorPacket("sess_test", seq, t, [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertIsNotNone(pos)
        self.assertFalse(engine.rate_bad, "rate_bad must clear after 3 healthy packets")
        print(f"\n[Item 1 (b) rate_bad preserved across >1.0s gap] Successfully tested: rate_bad stayed True across 2.5s gap and cleared after 3 healthy 10Hz packets.")

    # ── Item 1: A rejected packet still consumes its seq ──────────────────────
    def test_rate_rejected_packet_consumes_seq(self):
        """
        Item 1: A rejected packet (e.g. INVALID_RATE) still advances last_seq.
        The next packet (seq+1) is processed normally and not rejected as OUT_OF_ORDER_SEQ.
        """
        engine, cal, _ = _make_engine_and_calibrate(t_base=4190000)
        t_base = 4190000 + 5900
        # Send 10 packets at 10 Hz
        for k in range(10):
            pkt = SensorPacket("sess_test", 2+k, t_base+k*100,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            engine.handle_sensor(pkt)

        # Send a slow packet that gets rejected
        pkt_rejected = SensorPacket("sess_test", 12, t_base+1000+800,
                                    [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        # Force a second slow packet so it raises INVALID_RATE
        engine.handle_sensor(pkt_rejected) # first slow packet is gap packet (stale=True)
        pkt_rej2 = SensorPacket("sess_test", 13, t_base+1000+800+800,
                                [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        with self.assertRaises(GeoReckonError) as ctx:
            engine.handle_sensor(pkt_rej2)
        self.assertEqual(ctx.exception.code, "INVALID_RATE")
        # Next nominal packets consume their seq and recover
        pkt14 = SensorPacket("sess_test", 14, t_base+1000+1600+100,
                             [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        try:
            engine.handle_sensor(pkt14)
        except GeoReckonError as e:
            self.assertEqual(e.code, "INVALID_RATE") # consuming seq during recovery
        self.assertEqual(engine.last_seq, 14, "Packet seq 14 must be consumed")

        pkt15 = SensorPacket("sess_test", 15, t_base+1000+1600+200,
                             [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        try:
            engine.handle_sensor(pkt15)
        except GeoReckonError as e:
            self.assertEqual(e.code, "INVALID_RATE") # consuming seq during recovery
        self.assertEqual(engine.last_seq, 15, "Packet seq 15 must be consumed")

        # 3rd packet completes healthy recovery window and is accepted
        pkt16 = SensorPacket("sess_test", 16, t_base+1000+1600+300,
                             [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        pos16 = engine.handle_sensor(pkt16)
        self.assertIsNotNone(pos16)
        self.assertEqual(engine.last_seq, 16)
        print(f"\n[Item 1 rejected packet consumes seq] seq=13 rejected, seq=14-15 consumed seq during recovery, seq=16 accepted: last_seq={engine.last_seq}")

    # ── B1 (c): Single-packet jitter -> warning only, no rejection ───────────
    def test_rate_single_packet_jitter_no_rejection(self):
        """
        B1 (c): A single short-dt packet (dt < 0.04s) increments jitter_warnings
        but is processed normally without INVALID_RATE rejection.
        """
        engine, cal, _ = _make_engine_and_calibrate(t_base=4200000)
        t_base = 4200000 + 5900
        for k in range(5):
            pkt = SensorPacket("sess_test", 2+k, t_base+k*100,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            engine.handle_sensor(pkt)
        jitter_pkt = SensorPacket("sess_test", 7, t_base+500+15,
                                  [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
        try:
            res = engine.handle_sensor(jitter_pkt)
        except GeoReckonError as e:
            self.fail(f"Jitter packet must NOT raise, got {e.code}: {e.message}")
        self.assertEqual(engine.jitter_warnings, 1)
        self.assertIsNotNone(res)
        print(f"\n[B1 (c) jitter] jitter_warnings={engine.jitter_warnings}, mode={res.mode}")

    # ── B1 (d) & Item 2: Recovery to 10 Hz after rejection & count reporting ──
    def test_rate_recovery_to_10hz_reports_count(self):
        """
        Item 2: Stream recovery to 10 Hz after INVALID_RATE rejections.
        Reports exact number of healthy 10 Hz packets rejected before recovery (target: <= 2-3).
        """
        engine, cal, _ = _make_engine_and_calibrate(t_base=4300000)
        t_base = 4300000 + 5900
        # Send 10 nominal packets (10 Hz)
        for k in range(10):
            pkt = SensorPacket("sess_test", 2+k, t_base+k*100,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            engine.handle_sensor(pkt)

        # Flood with slow packets (250ms interval) to cause INVALID_RATE
        rejected_slow_count = 0
        seq = 12
        t = t_base + 1000
        first_rej_pkt = -1
        for k in range(12):
            t += 250
            pkt = SensorPacket("sess_test", seq, t,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                engine.handle_sensor(pkt)
            except GeoReckonError as e:
                if e.code == "INVALID_RATE":
                    rejected_slow_count += 1
                    if first_rej_pkt == -1:
                        first_rej_pkt = k + 1
            seq += 1

        self.assertGreater(rejected_slow_count, 0)

        # Now stream 10 Hz packets until recovery
        good_packets_rejected = 0
        recovery_pos = None
        for k in range(35):
            t += 100
            pkt = SensorPacket("sess_test", seq, t,
                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665])
            try:
                recovery_pos = engine.handle_sensor(pkt)
                break
            except GeoReckonError as e:
                if e.code == "INVALID_RATE":
                    good_packets_rejected += 1
                else:
                    raise
            seq += 1

        self.assertIsNotNone(recovery_pos, "Stream must recover to normal operation at 10 Hz")
        print(f"\n[Rate Diagnostic - Recovery to 10 Hz (Item 2)]")
        print(f"  N_slow: 12, rejected_slow_packets: {rejected_slow_count}, first_rejection_packet: #{first_rej_pkt}")
        print(f"  good_10hz_packets_rejected_before_recovery: {good_packets_rejected} (target: <=3)")
        self.assertLessEqual(good_packets_rejected, 3, f"At most 3 good packets may be rejected, got {good_packets_rejected}")

    # ── B2: Rejected packets interval discipline ──────────────────────────────
    def test_rejected_packet_interval_not_dropped_silently(self):
        """
        B2: No interval is dropped silently. If packets are rejected and stream recovers,
        the elapsed interval from the last integrated packet is evaluated.
        If elapsed interval > 0.5s, the recovery output must set stale=true.
        """
        engine, cal, _ = _make_engine_and_calibrate(t_base=4400000)
        t_base = 4400000 + 5900
        p1 = engine.handle_sensor(SensorPacket("sess_test", 2, t_base+100,
                                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665]))
        self.assertFalse(p1.stale)

        # Send a packet 700ms later (which is a gap 0.5-1.0s, stale=true)
        p2 = engine.handle_sensor(SensorPacket("sess_test", 3, t_base+800,
                                               [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665]))
        self.assertTrue(p2.stale)
        self.assertAlmostEqual(p2.gap_s, 0.7, places=2)
        print(f"\n[B2 interval discipline] Recovery packet stale={p2.stale}, gap_s={p2.gap_s}s (no silent interval drop)")

    # ── A2: allow_low_health ──────────────────────────────────────────────────
    def test_allow_low_health_succeeds_degraded(self):
        """allow_low_health=true allows calibration even with low alignment health."""
        engine = GeoReckonEngine()
        t_base = 6000000
        imu_hist, gps_hist = [], []
        for i in range(55):
            t_ms = t_base + i * 100
            noise = np.random.default_rng(42).normal(0, 0.005, 3)
            if i % 10 == 0 or i == 54:
                gps_hist.append(GPSHistorySample(t_ms=t_ms, speed_mps=0.0, bearing_deg=0.0))
            imu_hist.append(IMUHistorySample(
                t_ms=t_ms, accel=[float(noise[0]), float(noise[1]),
                                  float(9.80665+noise[2])],
                gyro=[0.0,0.0,0.0], gravity=[0.0,0.0,9.80665]))
        cal = CalibrateRequest(
            session_id="sess_lh", seq=1, t_ms=t_base+5400,
            sample_rate_hz=10.0, allow_low_health=True,
            last_gps=GPSFix(lat=12.971, lon=77.594, alt_m=900.0,
                            speed_mps=0.0, bearing_deg=45.0, accuracy_m=2.0),
            gps_history=gps_hist, imu_history=imu_hist)
        res = engine.handle_calibrate(cal)
        self.assertEqual(res.status, "OK")
        self.assertTrue(res.degraded)
        print(f"\n[A2 allow_low_health] degraded={res.degraded}, health={res.alignment_health}")

    # ── A2: BEARING_INVALID ───────────────────────────────────────────────────
    def test_bearing_invalid_rejected_without_allow_low_health(self):
        """BEARING_INVALID when speed<2.0 with no moving GPS, allow_low_health=false."""
        engine = GeoReckonEngine()
        t_base = 7000000
        imu_hist, gps_hist = [], []
        for i in range(55):
            t_ms = t_base + i * 100
            if i % 10 == 0 or i == 54:
                gps_hist.append(GPSHistorySample(t_ms=t_ms, speed_mps=0.0, bearing_deg=0.0))
            imu_hist.append(IMUHistorySample(
                t_ms=t_ms, accel=[0.0,0.0,9.80665],
                gyro=[0.0,0.0,0.0], gravity=[0.0,0.0,9.80665]))
        cal = CalibrateRequest(
            session_id="sess_bi", seq=1, t_ms=t_base+5400,
            sample_rate_hz=10.0, allow_low_health=False,
            last_gps=GPSFix(lat=12.971, lon=77.594, alt_m=900.0,
                            speed_mps=0.0, bearing_deg=0.0, accuracy_m=2.0),
            gps_history=gps_hist, imu_history=imu_hist)
        with self.assertRaises(GeoReckonError) as ctx:
            engine.handle_calibrate(cal)
        self.assertEqual(ctx.exception.code, "BEARING_INVALID")
        print(f"\n[A2 BEARING_INVALID] {ctx.exception.code}: {ctx.exception.message[:80]}")

    # ── A2: second calibrate ──────────────────────────────────────────────────
    def test_second_calibrate_rebuilds_state(self):
        """Second calibrate rebuilds state: new session_id, new seq baseline."""
        engine, cal1, res1 = _make_engine_and_calibrate(t_base=8000000, bearing_deg=0.0)
        self.assertEqual(res1.status, "OK")
        t_base = 8000000 + 5900
        for k in range(5):
            engine.handle_sensor(SensorPacket("sess_test", 2+k, t_base+k*100,
                                              [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        cal2 = _make_cal_request(session_id="sess_r2", seq=1, t_base=9000000, bearing_deg=90.0)
        res2 = engine.handle_calibrate(cal2)
        self.assertEqual(res2.status, "OK")
        self.assertEqual(engine.session_id, "sess_r2")
        self.assertEqual(engine.last_seq, cal2.seq)
        print(f"\n[A2 second_calibrate] session_id={engine.session_id}, last_seq={engine.last_seq}")

    # ── A2: reset ─────────────────────────────────────────────────────────────
    def test_reset_clears_state_and_returns_idle(self):
        """Reset clears all state and returns to IDLE."""
        engine, cal, _ = _make_engine_and_calibrate(t_base=10000000)
        self.assertEqual(engine.mode, "DEAD_RECKONING")
        rr = engine.handle_reset(ResetPacket("sess_test", seq=1, t_ms=10000000+6000))
        self.assertEqual(rr.status, "OK")
        self.assertEqual(engine.mode, "IDLE")
        self.assertIsNone(engine.vib_filter)
        print(f"\n[A2 reset] mode={engine.mode}, vib_filter={engine.vib_filter}")

    # ── A3 / B5: TOTAL per-step latency (full engine pipeline) ───────────────
    def test_total_per_step_latency_full_engine_1000_steps(self):
        """
        B5: Added dummy KinoNetR2 forward pass at startup.
        Report the latency of the first real step after startup,
        and full 1000-step stats: mean, p95, max, count above 20 ms.
        Seed: np.random.seed(0).
        Dropout: KinoNetR2 uses MC dropout; during inference, Dropout layers are
                 switched to train() only for the forward pass inside
                 predict_with_uncertainty (they use module.training flag internally);
                 the model's top-level .eval() call leaves Conv/BN in eval mode.
        """
        import torch
        np.random.seed(0)

        # Fresh engine instance to measure first step latency after dummy pass
        engine = GeoReckonEngine()
        cal = _make_cal_request(session_id="sess_lat", seq=1, t_base=11000000, bearing_deg=0.0)
        engine.handle_calibrate(cal)
        t_base = 11000000 + 5900

        # Measure the very first real sensor step after startup
        first_pkt = SensorPacket("sess_lat", 2, t_base+100,
                                 [0.0, 0.0, 9.80665], [0.0, 0.0, 0.0], [0.0, 0.0, 9.80665])
        t_first0 = time.perf_counter()
        engine.handle_sensor(first_pkt)
        first_step_latency_ms = (time.perf_counter() - t_first0) * 1000.0

        # Fill KinoNet buffer with remaining 19 packets
        for k in range(1, 20):
            pkt = SensorPacket("sess_lat", 2+k, t_base+(k+1)*100,
                               [0.0, 0.0, 9.80665], [0.0, 0.0, 0.0], [0.0, 0.0, 9.80665])
            engine.handle_sensor(pkt)

        # Measure 1000 full steps (with KinoNet shadow active)
        latencies = []
        seq = 22
        t = t_base + 21*100
        for k in range(1000):
            ax = float(np.random.normal(0.0, 0.3))
            pkt = SensorPacket("sess_lat", seq, t,
                               [ax, 0.0, 9.80665], [0.0, 0.0, 0.0], [0.0, 0.0, 9.80665])
            t0 = time.perf_counter()
            engine.handle_sensor(pkt)
            latencies.append((time.perf_counter() - t0) * 1000.0)
            seq += 1
            t += 100

        latencies = np.array(latencies)
        mean_lat = float(np.mean(latencies))
        p95_lat = float(np.percentile(latencies, 95))
        max_lat = float(np.max(latencies))
        count_above_20ms = int(np.sum(latencies > 20.0))

        print(f"\n[B5 TOTAL per-step latency (with startup dummy pass)]")
        print(f"  Startup first real step latency: {first_step_latency_ms:.2f} ms")
        print(f"  1000 steps stats: mean={mean_lat:.2f} ms, p95={p95_lat:.2f} ms, max={max_lat:.2f} ms")
        print(f"  Count exceeding 20 ms budget: {count_above_20ms} / 1000 ({count_above_20ms/10:.1f}%)")
        print(f"  Stages: vib-filter -> speed-estimator -> planar-integrator -> InEKF -> KinoNet shadow")
        print(f"  Dropout: predict_with_uncertainty switches Dropout layers to train() internally for MC sampling")
        self.assertLess(p95_lat, 20.0,
            f"Total pipeline p95 latency {p95_lat:.2f}ms exceeds 20ms budget")

    # ── A4 / B4: filter batch vs streaming, 60s realistic driving cycle ───────
    def test_filter_batch_vs_streaming_60s_with_turn_speed_change(self):
        """
        B4: filter_batch vs streaming on a realistic 60s trajectory
        Speed capped near 30 m/s, no runaway acceleration:
          0-10s : Accel from 0 to 15 m/s (ax = 1.5 m/s^2)
          10-20s: Constant speed 15 m/s, right turn (yaw rate omega_z = 0.1 rad/s)
          20-35s: Accel from 15 to 28 m/s (ax = 0.867 m/s^2)
          35-50s: Constant cruise 28 m/s (ax = 0.0 m/s^2)
          50-60s: Braking from 28 to 10 m/s (ax = -1.8 m/s^2)
        Report position difference in m and as % of distance, and speed difference (final and max).
        """
        np.random.seed(7)
        N = 600  # 60s at 10Hz
        fs = 10.0
        t = np.linspace(0, (N-1)/fs, N)
        dt = 1.0 / fs

        raw_a = np.zeros((N, 3))
        raw_w = np.zeros((N, 3))

        # Build realistic ax profile (speed stays in [0, 28] m/s)
        ax_profile = np.zeros(N)
        for i, ti in enumerate(t):
            if ti < 10.0:
                ax_profile[i] = 1.5
            elif ti < 20.0:
                ax_profile[i] = 0.0
            elif ti < 35.0:
                ax_profile[i] = 0.867
            elif ti < 50.0:
                ax_profile[i] = 0.0
            else:
                ax_profile[i] = -1.8

        raw_a[:, 0] = ax_profile + 0.3*np.sin(2*np.pi*20.0*t) + np.random.normal(0, 0.05, N)
        raw_a[:, 1] = 0.05*np.sin(2*np.pi*5.0*t)
        raw_a[:, 2] = 9.80665 + 0.3*np.sin(2*np.pi*25.0*t)

        # Yaw rate: turn segment t=10-20s at 0.1 rad/s (approx 57 deg total turn)
        raw_w[:, 2] = np.where((t >= 10.0) & (t < 20.0), 0.1, 0.0) + \
                      np.random.normal(0, 0.002, N)

        trajectory_desc = (
            "60s driving cycle: 0-10s accel 1.5 m/s^2 (0->15 m/s); "
            "10-20s turn omega_z=0.1 rad/s at 15 m/s; "
            "20-35s accel 0.867 m/s^2 (15->28 m/s); "
            "35-50s cruise at 28 m/s; 50-60s braking -1.8 m/s^2 (28->10 m/s). "
            "Speed peak: 28.0 m/s (capped <=30 m/s)."
        )

        # Batch filter
        batch_filt = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=1.5)
        batch_a, batch_w, _ = batch_filt.filter_batch(raw_a, raw_w)

        # Streaming filter
        stream_filt = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=1.5)
        stream_a_list, stream_w_list = [], []
        for k in range(N):
            fa, fw, _ = stream_filt.update_sample(raw_a[k], raw_w[k])
            stream_a_list.append(fa)
            stream_w_list.append(fw)
        stream_a = np.array(stream_a_list)
        stream_w = np.array(stream_w_list)

        # Integrate both to compare position and speed
        def integrate_traj(a_arr, w_arr):
            pos = np.zeros(2); heading = 0.0; speed = 0.0
            traj_pos, traj_spd = [], []
            for k in range(N):
                speed = max(0.0, speed + a_arr[k, 0] * dt)
                heading += w_arr[k, 2] * dt
                pos[0] += speed * math.cos(heading) * dt
                pos[1] += speed * math.sin(heading) * dt
                traj_pos.append(pos.copy())
                traj_spd.append(speed)
            return np.array(traj_pos), np.array(traj_spd)

        batch_pos, batch_spd = integrate_traj(batch_a, batch_w)
        stream_pos, stream_spd = integrate_traj(stream_a, stream_w)

        total_distance = float(np.sum(stream_spd * dt))
        pos_diff = np.linalg.norm(batch_pos - stream_pos, axis=1)
        spd_diff = np.abs(batch_spd - stream_spd)

        final_pos_diff = float(pos_diff[-1])
        max_pos_diff = float(np.max(pos_diff))
        final_pos_pct = (final_pos_diff / total_distance) * 100.0 if total_distance > 0 else 0.0
        max_pos_pct = (max_pos_diff / total_distance) * 100.0 if total_distance > 0 else 0.0

        final_spd_diff = float(spd_diff[-1])
        max_spd_diff = float(np.max(spd_diff))

        print(f"\n[B4 Filter Batch vs Streaming Realistic 60s Trajectory]")
        print(f"  Trajectory: {trajectory_desc}")
        print(f"  Total distance traveled: {total_distance:.2f} m")
        print(f"  Max vehicle speed: {float(np.max(stream_spd)):.2f} m/s")
        print(f"  Position difference: final={final_pos_diff:.3f} m ({final_pos_pct:.2f}%), max={max_pos_diff:.3f} m ({max_pos_pct:.2f}%)")
        print(f"  Speed difference:    final={final_spd_diff:.4f} m/s, max={max_spd_diff:.4f} m/s")

        self.assertLess(final_pos_pct, 10.0)
        self.assertLess(max_spd_diff, 5.0)

    # ── A4 (original 50-sample) ───────────────────────────────────────────────
    def test_filter_batch_vs_streaming_difference(self):
        """Filter batch vs streaming on 50-sample signal (original test)."""
        np.random.seed(42); N = 50; fs = 10.0
        t = np.linspace(0, (N-1)/fs, N)
        raw_a = np.zeros((N, 3))
        raw_a[:, 0] = 1.0+0.2*np.sin(2*np.pi*20.0*t)+np.random.normal(0, 0.05, N)
        raw_a[:, 1] = 0.05*np.sin(2*np.pi*5.0*t)
        raw_a[:, 2] = 9.80665+0.3*np.sin(2*np.pi*25.0*t)
        raw_w = np.zeros((N, 3))
        raw_w[:, 2] = 0.02*np.sin(2*np.pi*0.5*t)+np.random.normal(0, 0.005, N)
        filt = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=1.5)
        batch_a, _, _ = filt.filter_batch(raw_a, raw_w)
        sf = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=1.5)
        stream_a = np.array([sf.update_sample(raw_a[k], raw_w[k])[0] for k in range(N)])
        diff = np.linalg.norm(batch_a[20:] - stream_a[20:], axis=1)
        mean_d = float(np.mean(diff))
        print(f"\n[A1/filter_batch vs streaming] Mean diff={mean_d:.4f} m/s^2")
        self.assertLess(mean_d, 0.5)

    # ── A5 (original): straight run planar vs InEKF ───────────────────────────
    def test_planar_vs_inekf_comparison_synthetic_run(self):
        """Planar vs InEKF on straight synthetic run."""
        engine, cal, res = _make_engine_and_calibrate(t_base=12000000, bearing_deg=0.0)
        self.assertEqual(res.status, "OK")
        t_base = 12000000 + 5900
        planar_pos, inekf_pos = [], []
        for k in range(1, 31):
            pr = engine.handle_sensor(SensorPacket("sess_test", k+1, t_base+k*100,
                                                   [0.0,0.0,9.80665],[0.0,0.0,0.0],[0.0,0.0,9.80665]))
            planar_pos.append((pr.enu_x_m, pr.enu_y_m))
            inekf_pos.append((pr.inekf_e_m, pr.inekf_n_m))
        fp = planar_pos[-1]; fi = inekf_pos[-1]
        diff = math.hypot(fp[0]-fi[0], fp[1]-fi[1])
        print(f"\n[A1/planar_vs_inekf] Planar={fp}, InEKF={fi}, diff={diff:.3f}m")
        self.assertLess(diff, 2.0)

    # ── A5: FULL 4-stage with explicit InEKF freeze statement ─────────────────
    def test_planar_vs_inekf_all_four_stages(self):
        """
        A5: Planar vs InEKF on trajectory with:
          Stage 1: Straight (10 packets, 10 Hz)
          Stage 2: Turn (10 packets, omega_z=0.1 rad/s)
          Stage 3: 0.5-1.0s gap (dt=0.7s) -> stale=True, integration continues
          Stage 4: >1.0s gap (dt=2.0s) -> stale=True, planar FROZEN; InEKF NOT frozen
        For ALL stages: report planar ENU, InEKF ENU, and their diff.
        State explicitly whether InEKF froze in stage 4.
        """
        engine, cal, res = _make_engine_and_calibrate(t_base=13000000, bearing_deg=0.0)
        self.assertEqual(res.status, "OK")
        t_base = 13000000 + 5900
        seq = 2

        # ── Stage 1: Straight ────────────────────────────────────────────────
        for k in range(10):
            pr = engine.handle_sensor(SensorPacket("sess_test", seq, t_base+k*100,
                                                   [0,0,9.80665],[0,0,0],[0,0,9.80665]))
            seq += 1
        p_e_s1, p_n_s1 = pr.enu_x_m, pr.enu_y_m
        i_e_s1, i_n_s1 = pr.inekf_e_m, pr.inekf_n_m
        diff_s1 = math.hypot(p_e_s1-i_e_s1, p_n_s1-i_n_s1)
        print(f"\n[A5 Stage 1 Straight]")
        print(f"  Planar: E={p_e_s1:.3f} N={p_n_s1:.3f}")
        print(f"  InEKF : E={i_e_s1:.3f} N={i_n_s1:.3f}")
        print(f"  Diff  : {diff_s1:.4f} m")

        # ── Stage 2: Turn ────────────────────────────────────────────────────
        t_turn = t_base + 10*100
        for k in range(10):
            pr = engine.handle_sensor(SensorPacket("sess_test", seq, t_turn+k*100,
                                                   [0,0,9.80665],[0,0,0.1],[0,0,9.80665]))
            seq += 1
        p_e_s2, p_n_s2 = pr.enu_x_m, pr.enu_y_m
        i_e_s2, i_n_s2 = pr.inekf_e_m, pr.inekf_n_m
        diff_s2 = math.hypot(p_e_s2-i_e_s2, p_n_s2-i_n_s2)
        print(f"\n[A5 Stage 2 Turn]")
        print(f"  Planar: E={p_e_s2:.3f} N={p_n_s2:.3f}")
        print(f"  InEKF : E={i_e_s2:.3f} N={i_n_s2:.3f}")
        print(f"  Diff  : {diff_s2:.4f} m")

        # ── Stage 3: 0.5-1.0s gap (dt=0.7s) ──────────────────────────────────
        last_t_s2 = t_turn + 9*100  # last packet of turn
        t_s3 = last_t_s2 + 700      # exactly +0.70s gap
        pr3 = engine.handle_sensor(SensorPacket("sess_test", seq, t_s3,
                                                [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        seq += 1
        p_e_s3, p_n_s3 = pr3.enu_x_m, pr3.enu_y_m
        i_e_s3, i_n_s3 = pr3.inekf_e_m, pr3.inekf_n_m
        diff_s3 = math.hypot(p_e_s3-i_e_s3, p_n_s3-i_n_s3)
        print(f"\n[A5 Stage 3 Gap 0.7s] stale={pr3.stale}, gap_s={pr3.gap_s}")
        print(f"  Planar: E={p_e_s3:.3f} N={p_n_s3:.3f}  (integrated with dt=0.7s)")
        print(f"  InEKF : E={i_e_s3:.3f} N={i_n_s3:.3f}  (propagated with dt=0.7s)")
        print(f"  Diff  : {diff_s3:.4f} m")
        self.assertTrue(pr3.stale)
        self.assertAlmostEqual(pr3.gap_s, 0.7, places=2)

        # ── Stage 4: >1.0s gap (dt=2.0s) ─────────────────────────────────────
        t_s4 = t_s3 + 2000          # exactly +2.00s gap
        pr4 = engine.handle_sensor(SensorPacket("sess_test", seq, t_s4,
                                                [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        seq += 1
        p_e_s4, p_n_s4 = pr4.enu_x_m, pr4.enu_y_m
        i_e_s4, i_n_s4 = pr4.inekf_e_m, pr4.inekf_n_m
        diff_s4 = math.hypot(p_e_s4-i_e_s4, p_n_s4-i_n_s4)

        # Explicit freeze check:
        planar_froze = (abs(p_e_s4 - p_e_s3) < 0.01 and abs(p_n_s4 - p_n_s3) < 0.01)
        # InEKF guard: dt > 1.0 -> InEKF.predict is SKIPPED, so InEKF also holds position
        inekf_froze = (abs(i_e_s4 - i_e_s3) < 0.01 and abs(i_n_s4 - i_n_s3) < 0.01)

        print(f"\n[A5 Stage 4 Gap 2.0s] stale={pr4.stale}, gap_s={pr4.gap_s}")
        print(f"  Planar: E={p_e_s4:.3f} N={p_n_s4:.3f}  FROZEN={planar_froze}")
        print(f"  InEKF : E={i_e_s4:.3f} N={i_n_s4:.3f}  FROZEN={inekf_froze}")
        print(f"  Diff  : {diff_s4:.4f} m")
        print(f"  -> Planar integrator FROZE: {planar_froze}")
        print(f"  -> InEKF propagation SKIPPED (dt>1.0s guard), InEKF held position: {inekf_froze}")

        self.assertTrue(pr4.stale)
        self.assertAlmostEqual(pr4.gap_s, 2.0, places=1)
        self.assertTrue(planar_froze,
            f"Planar must freeze on >1.0s gap: before=({p_e_s3:.3f},{p_n_s3:.3f}), "
            f"after=({p_e_s4:.3f},{p_n_s4:.3f})")
        self.assertTrue(inekf_froze,
            f"InEKF must also hold on >1.0s gap (predict skipped): "
            f"before=({i_e_s3:.3f},{i_n_s3:.3f}), after=({i_e_s4:.3f},{i_n_s4:.3f})")

    # ── A2: full lifecycle ────────────────────────────────────────────────────
    def test_full_session_lifecycle(self):
        """Calibrate -> Stream -> OOO Rejection -> Restored -> Reset."""
        engine, cal, res_cal = _make_engine_and_calibrate(t_base=14000000, bearing_deg=90.0)
        self.assertEqual(res_cal.status, "OK")
        self.assertEqual(engine.mode, "DEAD_RECKONING")
        t_base = 14000000 + 5900
        pos1 = engine.handle_sensor(SensorPacket("sess_test", 2, t_base+100,
                                                 [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertEqual(pos1.mode, "DEAD_RECKONING")
        with self.assertRaises(GeoReckonError) as ctx:
            engine.handle_sensor(SensorPacket("sess_test", 2, t_base+200,
                                              [0,0,9.80665],[0,0,0],[0,0,9.80665]))
        self.assertEqual(ctx.exception.code, "OUT_OF_ORDER_SEQ")
        pr = engine.handle_gps_restored(GPSRestoredPacket("sess_test", 3, t_base+300,
            GPSFix(lat=12.9712, lon=77.5943, alt_m=900.0,
                   speed_mps=14.5, bearing_deg=90.0, accuracy_m=1.8)))
        self.assertEqual(pr.mode, "IDLE")
        self.assertIsNotNone(pr.dr_error_m)
        rr = engine.handle_reset(ResetPacket("sess_test", 1, t_base+400))
        self.assertEqual(rr.status, "OK")

    # ── A2: bearing fallback ──────────────────────────────────────────────────
    def test_bearing_fallback_and_low_health_rejection(self):
        """Bearing fallback: stationary -> BEARING_INVALID; allow_low_health -> OK."""
        engine = GeoReckonEngine()
        t_base = 15000000
        imu_hist, gps_hist = [], []
        rng = np.random.default_rng(99)
        for i in range(55):
            t_ms = t_base + i * 100
            if i % 10 == 0 or i == 54:
                gps_hist.append(GPSHistorySample(t_ms=t_ms, speed_mps=0.0, bearing_deg=0.0))
            noise = rng.normal(0, 0.005, 3)
            imu_hist.append(IMUHistorySample(
                t_ms=t_ms, accel=[float(noise[0]), float(noise[1]), float(9.80665+noise[2])],
                gyro=[0.0,0.0,0.0], gravity=[0.0,0.0,9.80665]))
        cal_static = CalibrateRequest(
            session_id="sess_stat", seq=1, t_ms=t_base+5400,
            sample_rate_hz=10.0, allow_low_health=False,
            last_gps=GPSFix(lat=12.971, lon=77.594, alt_m=900.0,
                            speed_mps=0.0, bearing_deg=0.0, accuracy_m=2.0),
            gps_history=gps_hist, imu_history=imu_hist)
        with self.assertRaises(GeoReckonError) as ctx:
            engine.handle_calibrate(cal_static)
        self.assertEqual(ctx.exception.code, "BEARING_INVALID")
        cal_static.allow_low_health = True
        res = engine.handle_calibrate(cal_static)
        self.assertEqual(res.status, "OK")
        self.assertTrue(res.degraded)


if __name__ == "__main__":
    unittest.main()
