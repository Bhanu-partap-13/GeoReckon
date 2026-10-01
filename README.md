# GeoReckon Production Engine (v3.2)
## Intelligent Dead Reckoning for Ground Vehicles in GNSS-Denied Environments
### Developed for ISRO Smart India Hackathon (SIH26168)

---

## 1. System Overview

GeoReckon solves continuous vehicle localization during complete GNSS blackouts (tunnels, underpasses, urban canyons) using only consumer MEMS IMU sensors on a smartphone. 

Unlike naive integration pipelines that diverge by over 1,000% within 45 seconds, this production engine implements an audited, physics-hardened multi-stage architecture:
1. **Dynamic Virtual Alignment Engine:** Uses Principal Component Analysis (PCA) on horizontal acceleration during pre-outage vehicle maneuvers and gravity reaction vectors to robustly solve the 3D phone-to-vehicle rotation matrix $R_b^v$.
2. **Kinematic Vibration Isolation:** 2nd-order zero-phase Butterworth filter ($f_c = 1.5\text{ Hz}$) with MAD impulse shock clipping, attenuating road roughness noise by $>15\text{ dB}$.
3. **Pre-Denial Steady-State Bias Estimator:** Identifies and subtracts forward accelerometer bias ($\hat{b}_a$) and gyroscope bias ($\hat{b}_g$) during the 10 seconds preceding blackout.
4. **Boundary Speed Continuity & Centripetal Kinematics:** Seeds initial forward speed $v_0$ from GNSS and continuously cross-validates speed during turns using $v = |a_{lat} / \omega_z|$.
5. **$SE_2(3)$ Invariant Extended Kalman Filter (InEKF):** Right-invariant Lie group state estimation with Non-Holonomic Constraints ($v_y = 0, v_z = 0$) and Adaptive Huber covariance gating preventing filter lockout.
6. **Topological Road Manifold Snapping:** Bounds orthogonal cross-track drift to the road boundary ($\pm 3.5\text{ m}$).

---

## 2. Directory Structure

```
GeoReckon_Production_Engine/
├── georeckon/
│   ├── src/
│   │   ├── __init__.py
│   │   ├── geometry.py                 # Lie algebra SO(3), skew, exp, left Jacobian, ENU
│   │   ├── virtual_alignment.py        # Robust PCA dynamic mount alignment
│   │   ├── vibration_filter.py         # Butterworth vibration filter & shock suppressor
│   │   ├── kinematics_speed_estimator.py# Boundary continuity, centripetal & ZUPT engine
│   │   ├── kinonet.py                  # Causal 1D CNN with Huber objective
│   │   ├── filter_inekf.py             # SE_2(3) Right-Invariant EKF with NHC
│   │   ├── map_matcher.py              # Topological road corridor snapping
│   │   ├── data_loader.py              # IO-VNBD dataset loader & continuous windowing
│   │   └── evaluator.py                # Standardized 45-60s outage trial evaluator
│   ├── tests/
│   │   ├── test_alignment.py           # PCA mount alignment unit tests
│   │   ├── test_inekf_lie.py           # Lie group kinematics & NHC tests
│   │   ├── test_speed_estimator.py     # Centripetal & ZUPT speed tests
│   │   ├── test_vibration_filter.py    # Frequency response & shock clipping tests
│   │   └── test_integration.py        # End-to-end closed loop blackout simulation test
│   ├── benchmarks/
│   │   ├── run_real_dataset_benchmarks.py  # Benchmarking across IO-VNBD Drives S1-S4
│   │   └── run_simulated_benchmarks.py     # Standalone simulation benchmark
│   └── configs/
│       └── default_config.json
├── requirements.txt
└── README.md
```

---

## 3. Installation & Verification

```bash
# 1. Install dependencies
pip install -r requirements.txt

# 2. Run complete unit test suite (12 tests)
$env:PYTHONPATH = "."
python -m unittest discover -s georeckon/tests

# 3. Run standalone simulation benchmark
python -m georeckon.benchmarks.run_simulated_benchmarks
```

---

## 4. Empirical Benchmark Summary (Real IO-VNBD Dataset, S1–S4)

Evaluated across 130 standardized 45–60s blackout windows on real smartphone telematics:

| Method | Median Endpoint Error (m) | Median Drift % | Windows $\le 10\%$ Drift | Windows $\le 5\%$ Drift ($\ge 95\%$ Acc) | Speed Accuracy % |
|---|---:|---:|---:|---:|---:|
| **Prior R2 (Mechanization)** | 4,578.1 m | 1,075.9% | 0.0% | 0.0% | 0.0% |
| **Prior R2 (InEKF + KinoNet)** | 1,854.6 m | 520.9% | 0.0% | 0.0% | 0.0% |
| **Held-Speed Baseline** | 343.5 m | 78.2% | 3.1% | 0.0% | 35.2% |
| **Fixed Physics Core (PCA+Cal+NHC)**| 458.4 m | 94.4% | 0.0% | 0.0% | 38.7% |
| **Fixed + Road Manifold (E5)** | **165.2 m** | **35.09%** | **23.8%** | **19.2%** | **63.3%** |
