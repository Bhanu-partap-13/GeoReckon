# GeoReckon WebSocket Protocol Specification v3.2

## 1. Overview & Architecture

GeoReckon connects a Python dead-reckoning engine running locally on a host machine to a Kotlin Android mobile application over a local WebSocket connection.

- **Transport:** WebSocket (Strict JSON serialization, NO `"event"` key inside payloads) via Flask-SocketIO / `simple-websocket`.
- **Default Port:** `5000` (can be overridden by the `PORT` environment variable).
- **Handshake Authentication:** The WebSocket handshake accepts an optional `"auth"` field (via Socket.IO connect payload dictionary, HTTP request headers, or URL query parameters). If configured, unauthorized connections are rejected at handshake.
- **Sampling Frequency:** Fixed at **10 Hz** ($\Delta t = 0.1\text{ s}$). The Android client down-samples/resamples all sensor readings to 10 Hz before transmission.
- **Rate Enforcement, Violation State (`rate_bad`) & Recovery:**
  - `last_seq` and `last_t_ms` are advanced **before** the rate check; a rejected packet still consumes its `seq` so stream recovery does not trigger `OUT_OF_ORDER_SEQ`.
  - Rate is computed as $\text{packets} / \Delta t_{\text{window}}$ over a sliding window of up to 20 packets: $\text{rate} = (N - 1) / ((t_{\text{newest}} - t_{\text{oldest}}) / 1000.0)$, with gaps **INCLUDED**.
  - If $N \ge 10$ (or $\ge 2$ consecutive slow packets) and computed rate is outside **8–12 Hz**, `INVALID_RATE` is raised and the server enters a persistent `rate_bad = true` state.
  - While in `rate_bad = true`, all incoming slow packets ($\Delta t \ge 0.5\text{ s}$) are rejected immediately.
  - To exit `rate_bad = true`, the client must send a healthy 10 Hz stream ($\Delta t < 0.5\text{ s}$). Once 3 healthy packets (2 intervals, $\approx 0.20\text{ s}$) are accumulated with average rate within 8–12 Hz, `rate_bad` is cleared (recovery costs at most 2–3 good packets).
  - **Jitter** ($\Delta t < 0.04\text{ s}$): client timestamp glitch / burst arrival. Packet is processed normally; `jitter_warnings` counter is incremented without raising `INVALID_RATE`.
- **Latency Budget:** $\le 20\text{ ms}$ processing budget per sensor step on CPU.
- **Session Identity:** Server session key is `request.sid` (SocketIO connection ID). The client-provided `session_id` is for logging/telemetry tracing.
- **Engine Core: Wired Stages:** `VirtualInVehicleAligner` $\to$ `KinematicVibrationFilter` $\to$ `KinematicSpeedEstimator` $\to$ **Planar Kinematic Integrator (`evaluator.py:146-150`, Primary Position Source)** + **Lie-Group InEKF (`filter_inekf.py`, Secondary / Covariance Source)**.
- **Position Source Clarification:** Primary navigation telemetry (`lat`, `lon`, `enu_x_m`, `enu_y_m`, `speed_mps`, `bearing_deg`, `dr_distance_m`, `dr_error_m`) comes directly from the benchmarked Planar Kinematic Integrator (`evaluator.py:146-150`). The 15-state InEKF on $SE_2(3)$ runs in parallel to provide 2D covariance (`get_position_covariance_2d`) for heuristic confidence decay and outputs secondary positions `inekf_e_m` and `inekf_n_m` for side-by-side comparison.
- **KinoNet Shadow Mode:** `KinoNetR2` neural network runs in shadow mode; its estimated speed (`kinonet_speed_mps`) and variance (`kinonet_var`) are reported in telemetry but **not** fused into the InEKF state or position. Note that `kinonet_var` is stochastic (computed from 5 Monte Carlo dropout forward passes in `predict_with_uncertainty`).
- **KinoNet Weights Path:** Loaded at startup from the path in environment variable `KINONET_WEIGHTS`. If unset, it resolves relative to the server source file (`Path(__file__).resolve().parent.parent.parent / "InertiaX" / "kinonet_r2_best.pth"`).
- **Benchmark & Training Disclaimers:**
  - Reference baseline: **94.4% median drift** on telematics without map matching (quoted from `README.md` batch-filtered benchmark using `filter_batch`/`filtfilt`; real-time streaming accuracy is unmeasured until Phase 9).
  - **Streaming vs Batch Filter Difference:** Streaming filter output differs from the batch benchmark by 4.86% of distance on this profile (noise-free synthetic), and the 94.4% figure does not apply.
  - The 35.09% figure is strictly labeled as "with ground-truth road corridor".
  - `KinoNetR2` was trained on the IO-VNBD dataset; its shadow output is not independent and is not validated as a verified primary speed source.

---

## 2. Per-Stage Data Routing & Reference Frames

| Pipeline Stage | Input Vector | Reference Frame | Filtering | Gravity Status & Biases | Source Reference |
|---|---|---|---|---|---|
| **Mount Alignment** (`VirtualInVehicleAligner`) | $\mathbf{g}_{\text{hist}}, \mathbf{a}_{\text{hist}}, \mathbf{v}_{\text{hist}}, \mathbf{t}_{\text{hist}}$ | Phone Body ($b$) | Raw History | Raw gravity & accel; computes $R_{b\to v}$ & `alignment_health` | `virtual_alignment.py:45-100` |
| **Bias Estimation** (Pre-outage calibration) | $\mathbf{a}_{\text{hist}}, \mathbf{g}_{\text{hist}}, \mathbf{w}_{\text{hist}}$ | Phone $\to$ Vehicle | 5 s slice ($N=50$) | Evaluates $\bar{a}_{\text{fwd}} - \Delta v / \Delta t$; forward bias $b_{\text{fwd}} \in [-0.3, 0.3]$, Gyro bias $\mathbf{b}_\omega = \text{median}(\mathbf{w})$ | `evaluator.py:93-100` |
| **Vibration Filter** (`KinematicVibrationFilter`) | $\mathbf{a}_{\text{phone}}, \mathbf{\omega}_{\text{phone}} - \mathbf{b}_\omega$ | Phone Body ($b$) | `update_sample` (20-sample buffer) | Raw specific force ($+9.80665$ present); gyro bias removed in phone frame | `vibration_filter.py:64-77`, `evaluator.py:103` |
| **Speed Estimator** (`KinematicSpeedEstimator`) | $a_{\text{fwd,veh}}, a_{\text{lat,veh}}, \omega_{\text{yaw,veh}}$ | Vehicle Frame ($v$) | From `update_sample` | Gravity is subtracted in the **phone frame BEFORE rotating** (`lin_veh = (R_b_to_v @ (clean_a - g_phone).T).T`); $b_{\text{fwd}}$ applied | `evaluator.py:123-140`, `kinematics_speed_estimator.py:28` |
| **Planar Integrator (Primary Position)** | $v_{\text{kinematic}}, \omega_{\text{yaw,veh}}$ | Vehicle $\to$ World ENU | From Speed Estimator | Integrates heading $\theta_{k} = \theta_{k-1} + \omega_{\text{yaw,veh}} \Delta t$ and position $\mathbf{p}_k = \mathbf{p}_{k-1} + v_{\text{kinematic}} [\cos\theta_k, \sin\theta_k]^\top \Delta t$ | `evaluator.py:146-150` |
| **KinoNet Shadow** (`KinoNetR2`) | $[\mathbf{a}_{\text{veh}}, \mathbf{\omega}_{\text{veh}}] \in \mathbb{R}^{1 \times 6 \times 20}$ | Vehicle Frame ($v$) | **Unfiltered** | Rotated to vehicle frame, biases subtracted, gravity ($9.80665$) subtracted from vehicle Z | `georeckon_pipeline.py:1277-1284, 1969-1984` |
| **Lie-Group InEKF** (`InEKF.predict`) | $\mathbf{a}_{\text{phone,specific}}, \mathbf{\omega}_{\text{phone}} - \mathbf{b}_\omega$ | Phone Body ($b$) | From `update_sample` | Receives phone-frame **specific force** (gravity present); rotates via $R_{b\to w}$ and adds $\mathbf{g}_{\text{world}} = [0, 0, -9.80665]$ in world frame | `filter_inekf.py:80-131` |
| **InEKF NHC / Speed** (`update_chassis_velocity`) | $[v_{\text{kinematic}}, 0, 0]^\top$ | Vehicle Frame ($v$) | Kinematic Speed | Enforces zero lateral/vertical velocity ($v_y \approx 0, v_z \approx 0$) & forward speed $v_x = v_{\text{kinematic}}$ using $B = R_{b\to v} R_{b\to w}^\top$ | `filter_inekf.py:179-196` |

### Required Android Sensor Types & Stationary Check
- The client MUST transmit raw `Sensor.TYPE_ACCELEROMETER` (gravity included, specific force), **NEVER** `Sensor.TYPE_LINEAR_ACCELERATION`, alongside `Sensor.TYPE_GRAVITY`.
- **Stationary Sanity Test:** When the phone rests flat on a table, the readings MUST evaluate to $\mathbf{a}_{\text{phone}} \approx [0.0, 0.0, 9.80665]^\top\text{ m/s}^2$ and $\mathbf{g}_{\text{phone}} \approx [0.0, 0.0, 9.80665]^\top\text{ m/s}^2$.

---

## 3. Clock, Timing, Sequence, & Packet Gap Discipline

- **Monotonic Clock:** All timestamps (`t_ms`) MUST originate from a single monotonic clock on the client device (Android `SystemClock.elapsedRealtime()`). No epoch time.
- **Integration Delta ($\Delta t$):** $\Delta t = (t_{\text{ms}, k} - t_{\text{ms}, k-1}) / 1000.0\text{ s}$. The integration step uses the actual elapsed $\Delta t$, never a hardcoded constant.
- **Packet Gap Handling & Rejected Interval Discipline:**
  - $\Delta t \le 0.5\text{ s}$: Normal integration.
  - $0.5\text{ s} < \Delta t \le 1.0\text{ s}$: Primary planar integrator integrates using real $\Delta t$, holding last known speed and latest yaw rate; secondary InEKF propagates with real $\Delta t$; server sets `stale = true` and `gap_s = dt`.
  - $\Delta t > 1.0\text{ s}$: The motion during the gap is dropped; position resumes from the last point; the offset is not recovered; secondary InEKF skips propagation ($\Delta t > 1.0\text{ s}$ guard in `filter_inekf.py:84`); server replies with `stale = true` and `gap_s = dt`.
  - **Rejected Packets:** No interval is dropped silently. When packets are rejected (e.g. `INVALID_RATE`), the server tracks the elapsed interval from the last successfully integrated packet (`last_integrated_t_ms`). Upon stream recovery, if the unintegrated elapsed interval is $\le 1.0\text{ s}$, it is integrated with real $\Delta t$ and flagged `stale = true`; if $> 1.0\text{ s}$, motion during the gap is dropped, resuming position from the last point with `stale = true` and `gap_s = \Delta t`.
- **Sequence Counter (`seq`):**
  - Scoped per connection, strictly increasing integer.
  - `calibrate` and `reset` packets are exempt from the ordering check and establish a new baseline (e.g. `reset` carries `seq: 1`).
  - Gaps in `seq` during tracking are allowed and logged as dropped packets.
  - A rejected packet (e.g. schema error, `INVALID_RATE`) consumes its `seq`; `last_seq` is advanced before the rate check so stream recovery does not trigger `OUT_OF_ORDER_SEQ`.
  - If a second `calibrate` is received during an active session, all filter and estimator state is rebuilt, establishing a new baseline.

---

## 4. Alignment Health, Validation, & Geodesics

### 4.1 Alignment Health & Rejection Threshold
`VirtualInVehicleAligner.compute_alignment_from_history` calculates:
$$\text{ratio} = \frac{\lambda_1}{\max(\lambda_2, 10^{-6})}, \quad \text{alignment\_health} = \text{clip}\left(\frac{\text{ratio} - 1.0}{4.0}, 0.05, 1.0\right)$$
- **2D Horizontal PCA Formulation (`virtual_alignment.py:53-58`):** The aligner first projects out the vertical gravity axis $\mathbf{u}_{\text{up}}$. On the resulting horizontal plane, PCA finds the principal axes of excitation. $\lambda_1 = \text{eigvals}[-1]$ is the longitudinal acceleration/braking variance and $\lambda_2 = \text{eigvals}[-2]$ is the lateral variance.
- **Client History Recommendation:** The Android client should send the longest available pre-outage buffer (up to 600 samples / 60 s) to maximize the probability of capturing vehicle acceleration or braking events.
- **Algorithm Return Value (`virtual_alignment.py:92, 99`):** When health is low, `compute_alignment_from_history` returns the computed PCA rotation matrix `R_b_to_v` alongside the low health score (it does NOT return identity). Due to lack of longitudinal variance excitation, this matrix contains an **unreliable forward axis**.
- **Observed Unit-Test Values:**
  - Dynamic acceleration (`test_ideal_alignment`): $\text{ratio} \to \infty \implies \text{health} = \mathbf{1.0}$.
  - Random isotropic noise (`test_orthogonality`): $\text{ratio} \approx 1.0 \implies \text{health} = \mathbf{0.05}$.
- **Rejection Threshold:** If `alignment_health` < `ALIGN_MIN_HEALTH` (provisional threshold `0.25`), calibration is rejected with `ALIGNMENT_HEALTH_LOW`, and **no session state is created or modified**.
- **Standstill & Low Health Override:** If the vehicle is stationary before an outage or during desk testing, the client sets `allow_low_health = true` in `calibrate`. The server then proceeds using the PCA matrix with unreliable forward axis, sets `degraded = true` in `calibrate_done` and `position`, and flags navigation as degraded.
- **Client UI Policy:** The Android app must **NOT** set `allow_low_health = true` silently. It requires an explicit user toggle in settings/debug options and must display a prominent, persistent `"DEGRADED CALIBRATION"` badge on the HUD.

### 4.2 Geodesic Projections (Spherical Earth Model, R = 6,371,000 m)
Consistent radius $R = 6,371,000.0\text{ m}$ relative to $\text{lat}_0$ (matching `georeckon.src.geometry.enu_from_latlon:58-60`):

$$\Delta \text{lat} = \text{lat} - \text{lat}_0, \quad \Delta \text{lon} = \text{lon} - \text{lon}_0$$

**Forward Transform (Lat/Lon $\to$ ENU):**
$$x_{\text{east}} = R \cdot (\text{lon} - \text{lon}_0) \cdot \cos\left(\text{lat}_0 \cdot \frac{\pi}{180^\circ}\right) \cdot \frac{\pi}{180^\circ}$$
$$y_{\text{north}} = R \cdot (\text{lat} - \text{lat}_0) \cdot \frac{\pi}{180^\circ}$$

**Inverse Transform (ENU $\to$ Lat/Lon):**
$$\text{lat} = \text{lat}_0 + \frac{y_{\text{north}}}{R} \cdot \frac{180^\circ}{\pi}$$
$$\text{lon} = \text{lon}_0 + \frac{x_{\text{east}}}{R \cdot \cos\left(\text{lat}_0 \cdot \frac{\pi}{180^\circ}\right)} \cdot \frac{180^\circ}{\pi}$$

*Note on Altitude:* `alt_m` and `enu_z_m` are copied directly from the last valid GPS fix and are not dynamically estimated.

### 4.3 Heading & Bearing Conversions & Initial Heading Fallback
- Compass Bearing ($\psi_{\text{bearing}}$): Degrees $[0^\circ, 360^\circ)$ clockwise from North ($0^\circ=\text{N}, 90^\circ=\text{E}, 180^\circ=\text{S}, 270^\circ=\text{W}$).
- Mathematical Heading ($\theta_{\text{math}}$): Degrees/radians counter-clockwise from East.
  - $\theta_{\text{math}} = (90^\circ - \psi_{\text{bearing}}) \pmod{360^\circ}$
  - $\psi_{\text{bearing}} = (90^\circ - \theta_{\text{math}}) \pmod{360^\circ}$
- **Initial Heading Validation & Fallback:**
  - If `last_gps.speed_mps < 2.0` m/s, GNSS velocity-derived bearing is considered invalid/noisy.
  - **Fallback:** The server searches backward through `gps_history` for the most recent sample where `speed_mps >= 2.0` m/s and uses its `bearing_deg`.
  - If no sample in `gps_history` satisfies `speed_mps >= 2.0` m/s, calibration is rejected with error code `BEARING_INVALID`, **unless `allow_low_health = true`** is set by the client. No other bypass exists.
  - **Degraded initial heading (allow_low_health=true bypass):** The server uses `last_gps.bearing_deg` as the initial heading regardless of speed. This bearing is derived from a stationary or slow GNSS fix and is **unreliable** — the true vehicle orientation is unknown. `degraded = true` is set on all outputs for the session.

---

## 5. Operating Modes & Confidence Metric

### 5.1 Operating Modes
- `IDLE`: Session connected or reset, awaiting calibration.
- `DEAD_RECKONING`: Active GNSS blackout navigation.
- *(Note: Calibration is synchronous. Upon `gps_restored`, the server replies with final metrics and immediately transitions mode back to `IDLE`, requiring a new `calibrate` before the next outage).*

### 5.2 Confidence Metric Formulation
Confidence is derived from `get_position_covariance_2d()` blended with an exponential outage duration decay:

$$\sigma_{\text{pos}} = \sqrt{\text{tr}(\text{get\_position\_covariance\_2d}())}\text{ (meters)}$$
$$\text{decay} = \exp\left(-\frac{t_{\text{ms}} - t_{\text{blackout\_start}}}{60000.0}\right)$$
$$\text{confidence} = \text{clip}\left(\exp\left(-\frac{\sigma_{\text{pos}}}{10.0}\right) \cdot \text{decay}, 0.05, 1.0\right)$$
where $t_{\text{blackout\_start}}$ is the timestamp of the first `sensor` packet after calibration.

*Note: InEKF covariance without external position updates is typically overconfident; the time decay provides an empirical bounds safeguard. `confidence` is a heuristic quality index, not a probability.*

---

## 6. WebSocket Protocol Messages

*Note: Payloads contain NO internal `"event"` key; message routing is handled by Socket.IO event names.*

### 6.1 Client $\to$ Server Messages

#### Event: `calibrate`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 1,
  "t_ms": 1727632400000,
  "sample_rate_hz": 10.0,
  "allow_low_health": false,
  "last_gps": {
    "lat": 12.9715987,
    "lon": 77.5945627,
    "alt_m": 920.5,
    "speed_mps": 18.5,
    "bearing_deg": 45.0,
    "accuracy_m": 2.1
  },
  "gps_history": [
    {"t_ms": 1727632395000, "speed_mps": 17.8, "bearing_deg": 44.8},
    {"t_ms": 1727632398000, "speed_mps": 18.2, "bearing_deg": 45.0},
    {"t_ms": 1727632400000, "speed_mps": 18.5, "bearing_deg": 45.0}
  ],
  "imu_history": [
    {
      "t_ms": 1727632395000,
      "accel": [0.12, 0.05, 9.80665],
      "gyro": [0.001, -0.002, 0.005],
      "gravity": [0.0, 0.0, 9.80665]
    }
  ]
}
```
*Validation & Interpolation Rules:*
- `imu_history` length MUST be $50 \le N \le 600$ (rejects with `HISTORY_TOO_SHORT` or `HISTORY_TOO_LONG`).
- `gps_history` MUST have $\ge 3$ points spanning $\ge 3.0\text{ s}$.
- The server linearly interpolates `speed_mps` from `gps_history` onto `imu_history` timestamps and trims any IMU samples outside the GPS time span.
- **Buffer Seeding (New Design):** Pre-outage history received in `calibrate` directly seeds both the 20-sample sliding buffer of `KinematicVibrationFilter` and the 20-sample window of `KinoNetR2`, eliminating any 2-second startup warm-up latency.

#### Event: `sensor`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 142,
  "t_ms": 1727632414200,
  "accel": [0.45, -0.08, 9.78],
  "gyro": [0.003, 0.001, -0.012],
  "gravity": [0.02, 0.01, 9.805]
}
```

#### Event: `gps_restored`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 601,
  "t_ms": 1727632460100,
  "gps": {
    "lat": 12.9789012,
    "lon": 77.6012345,
    "alt_m": 921.0,
    "speed_mps": 20.1,
    "bearing_deg": 46.2,
    "accuracy_m": 3.0
  }
}
```

#### Event: `reset`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 1,
  "t_ms": 1727632460200
}
```

#### Event: `ping` (Application Heartbeat / RTT measurement)
```json
{
  "t_ms": 1727632460250
}
```
*Heartbeat & Transport Specifications:*
- **Sender:** Client emits `ping` to measure round-trip latency and verify active engine responsiveness.
- **Expected Reply:** Server immediately echoes client timestamp in `pong` alongside its own timestamp.
- **Distinct from Transport Heartbeat:** This event-level `ping`/`pong` operates above the WebSocket transport to evaluate Python engine responsiveness; it is distinct from lower-level Engine.IO ping/pong packets.
- **Timeout & Link Health:** If client receives no `pong` within 2.0 s (or if 3 consecutive pings fail), the client declares the link degraded/stalled and triggers a connection refresh.

---

### 6.2 Server $\to$ Client Messages

#### Event: `pong` (Reply to `ping`)
```json
{
  "t_ms": 1727632460250,
  "server_t_ms": 1727632460252
}
```
*Payload Description:*
- `t_ms`: Exact monotonic timestamp echoed from client's `ping` for RTT calculation ($t_{\text{recv}} - t_{\text{ms}}$).
- `server_t_ms`: Server-side epoch/monotonic timestamp (in ms) when the pong response was dispatched.

#### Event: `calibrate_done`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 1,
  "t_ms": 1727632400010,
  "status": "OK",
  "degraded": false,
  "r_phone_to_vehicle": [
    [1.0, 0.0, 0.0],
    [0.0, 1.0, 0.0],
    [0.0, 0.0, 1.0]
  ],
  "alignment_health": 0.95,
  "fwd_accel_bias_mps2": 0.012,
  "gyro_bias_radps": [0.0001, -0.0002, 0.0005],
  "latency_ms": 2.4
}
```

#### Event: `position` (Emitted on `sensor` and on `gps_restored`)
```json
{
  "session_id": "sess_20260929_001",
  "seq": 142,
  "t_ms": 1727632414200,
  "lat": 12.9723456,
  "lon": 77.5952341,
  "alt_m": 920.5,
  "enu_x_m": 72.85,
  "enu_y_m": 82.71,
  "enu_z_m": 0.0,
  "speed_mps": 18.42,
  "bearing_deg": 45.3,
  "mode": "DEAD_RECKONING",
  "degraded": false,
  "confidence": 0.88,
  "is_stopped": false,
  "shock_detected": false,
  "stale": false,
  "gap_s": 0.0,
  "kinonet_speed_mps": 18.35,
  "kinonet_var": 0.12,
  "inekf_e_m": 72.91,
  "inekf_n_m": 82.65,
  "dr_lat": null,
  "dr_lon": null,
  "dr_error_m": null,
  "dr_distance_m": 850.4,
  "latency_ms": 3.8
}
```

*Final Reentry Position Payload on `gps_restored`:*
```json
{
  "session_id": "sess_20260929_001",
  "seq": 601,
  "t_ms": 1727632460100,
  "lat": 12.9789012,
  "lon": 77.6012345,
  "alt_m": 921.0,
  "enu_x_m": 720.50,
  "enu_y_m": 810.30,
  "enu_z_m": 0.0,
  "speed_mps": 20.1,
  "bearing_deg": 46.2,
  "mode": "IDLE",
  "degraded": false,
  "confidence": 1.0,
  "is_stopped": false,
  "shock_detected": false,
  "stale": false,
  "gap_s": 0.0,
  "kinonet_speed_mps": 20.05,
  "kinonet_var": 0.08,
  "inekf_e_m": 721.10,
  "inekf_n_m": 809.85,
  "dr_lat": 12.9788500,
  "dr_lon": 77.6011800,
  "dr_error_m": 8.12,
  "dr_distance_m": 1120.4,
  "latency_ms": 1.9
}
```

#### Event: `reset_done`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 1,
  "t_ms": 1727632460205,
  "status": "OK",
  "mode": "IDLE",
  "latency_ms": 0.2
}
```

#### Event: `error`
```json
{
  "session_id": "sess_20260929_001",
  "seq": 1,
  "t_ms": 1727632400010,
  "code": "ALIGNMENT_HEALTH_LOW",
  "message": "Computed alignment health 0.12 is below ALIGN_MIN_HEALTH (0.25). Re-attempt during acceleration or braking or set allow_low_health=true.",
  "latency_ms": 0.4
}
```

---

## 7. Complete Error Code Reference Table

| Error Code | HTTP/WS Context | Cause | Recovery Action |
|---|---|---|---|
| `NOT_CALIBRATED` | State violation | `sensor` received before `calibrate` | Send valid `calibrate` event first. |
| `INVALID_SCHEMA` | Payload error | Missing required key or wrong data type | Verify JSON field names and structure. |
| `NAN_OR_INF` | Data corruption | Floats contain NaN, null, or Inf | Check phone IMU hardware health. |
| `OUT_OF_ORDER_SEQ` | Transport error | `seq` $\le$ previous processed `seq` (exempt on `calibrate`/`reset`) | Reset sequence counter on client. |
| `INVALID_RATE` | Rate violation | 2.0 s moving average rate outside 8–12 Hz | Resample client IMU stream to 10 Hz. Packet dropped. |
| `HISTORY_TOO_SHORT` | Calibration | IMU history $< 50$ or GPS history $< 3$ | Accumulate $\ge 5\text{ s}$ pre-outage buffer. |
| `HISTORY_TOO_LONG` | Calibration | IMU history contains $> 600$ samples | Trim pre-outage buffer to $\le 60\text{ s}$. |
| `ALIGNMENT_HEALTH_LOW`| Calibration | `alignment_health` < `ALIGN_MIN_HEALTH` | Re-attempt during acceleration or braking or set `allow_low_health=true`. |
| `BEARING_INVALID`     | Calibration | Speed $< 2.0$ m/s with no valid historical bearing | Move vehicle before calibration or set `allow_low_health=true`. |
| `INTERNAL_ERROR`      | Engine crash | Uncaught Python runtime exception | Inspect server logs and restart session. |
