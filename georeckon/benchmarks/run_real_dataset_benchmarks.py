"""
GeoReckon Real-World Dataset Benchmark Runner
Executes comprehensive benchmark evaluation across the synchronized IO-VNBD telematics dataset.
Evaluates 130 standardized 45-60 second GNSS blackout windows per drive.
Produces summary CSV and JSON benchmark reports.
"""

import os
import json
import argparse
from pathlib import Path
import numpy as np
import pandas as pd

from georeckon.src.data_loader import load_drive
from georeckon.src.evaluator import extract_standardized_outages, evaluate_trial


def run_benchmarks(data_dir: str, output_dir: str):
    data_path = Path(data_dir)
    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    print("=" * 75)
    print("      GEORECKON REAL-WORLD BENCHMARK SUITE // IO-VNBD TELEMATICS")
    print("=" * 75)

    drives = ['S1', 'S2', 'S3a', 'S3b', 'S3c', 'S4']
    all_summaries = []

    for d_name in drives:
        s_file = data_path / d_name / f"S-{d_name}.csv"
        v_file = data_path / d_name / f"V-{d_name}.csv"

        if not s_file.exists():
            print(f"Skipping Drive {d_name} (file not found at {s_file})")
            continue

        print(f"\nProcessing Drive {d_name}...")
        drive = load_drive(str(s_file), str(v_file) if v_file.exists() else None)
        print(f"Loaded {len(drive.t):,d} samples ({drive.t[-1]-drive.t[0]:.1f} s duration)")

        trials = list(extract_standardized_outages(drive))
        print(f"Extracted {len(trials)} eligible non-overlapping 45-60s blackout windows")

        if not trials:
            continue

        # Evaluate Pipeline Variants
        methods = {
            'Held_Speed': dict(use_map=False, use_fixed_physics=False),
            'Fixed_Physics_Core': dict(use_map=False, use_fixed_physics=True),
            'Fixed_Physics_Plus_Road_Corridor': dict(use_map=True, use_fixed_physics=True)
        }

        for m_name, m_cfg in methods.items():
            records = []
            for tr in trials:
                res = evaluate_trial(drive, tr, use_map=m_cfg['use_map'], use_fixed_physics=m_cfg['use_fixed_physics'])
                res['drive'] = d_name
                res['method'] = m_name
                records.append(res)

            df_m = pd.DataFrame(records)
            median_endpoint = float(df_m['endpoint_m'].median())
            p90_endpoint = float(df_m['endpoint_m'].quantile(0.90))
            median_drift = float(df_m['drift_pct'].median())
            pct_below_10 = float((df_m['drift_pct'] <= 10.0).mean() * 100.0)
            pct_below_5 = float((df_m['drift_pct'] <= 5.0).mean() * 100.0)
            mean_speed_acc = float(df_m['speed_acc_pct'].mean())

            summary_row = {
                'Drive': d_name,
                'Method': m_name,
                'Windows': len(trials),
                'Median_Endpoint_m': round(median_endpoint, 1),
                'P90_Endpoint_m': round(p90_endpoint, 1),
                'Median_Drift_Pct': round(median_drift, 2),
                'Windows_le_10pct': round(pct_below_10, 1),
                'Windows_le_5pct': round(pct_below_5, 1),
                'Mean_Speed_Accuracy_Pct': round(mean_speed_acc, 1)
            }
            all_summaries.append(summary_row)
            print(f"  [{m_name:32s}] Median Drift: {median_drift:6.2f}% | Drift <= 5%: {pct_below_5:4.1f}% | Speed Acc: {mean_speed_acc:5.1f}%")

    if all_summaries:
        df_summary = pd.DataFrame(all_summaries)
        csv_file = out_path / "benchmark_summary.csv"
        df_summary.to_csv(csv_file, index=False)
        json_file = out_path / "benchmark_metrics.json"
        json_file.write_text(json.dumps(all_summaries, indent=2))
        print(f"\nSaved comprehensive benchmark reports to:\n- {csv_file}\n- {json_file}")
        print("\nOverall Benchmark Summary Table:")
        print(df_summary.to_string(index=False))


if __name__ == '__main__':
    default_data = r"C:\Users\prann\.gemini\antigravity\scratch\isro-idr-navigation\data\iovnb\Synchronised V abd S datasets\Categorised IOVNB Dataset\S (Driver A)"
    default_out = r"C:\Users\prann\Downloads\GeoReckon_Production_Engine\georeckon\benchmarks\reports"
    parser = argparse.ArgumentParser(description="GeoReckon Benchmark Runner")
    parser.add_argument("--data", default=default_data, help="Path to IO-VNBD driver folder")
    parser.add_argument("--out", default=default_out, help="Output directory for reports")
    args = parser.parse_args()

    run_benchmarks(args.data, args.out)
