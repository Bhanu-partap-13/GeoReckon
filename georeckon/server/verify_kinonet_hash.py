"""
Script to compute and compare the SHA-256 hashes of KinoNet-R2 source code:
1. Verbatim class source in georeckon/server/kinonet_r2.py
2. Original class source in InertiaX/georeckon_pipeline.py (lines 1100-1247)
And explain the difference between range 1100-1198 vs 1100-1247.
"""

import hashlib
from pathlib import Path


def extract_original_kinonet_source():
    pipeline_path = Path(__file__).resolve().parent.parent.parent / "InertiaX" / "georeckon_pipeline.py"
    with open(pipeline_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    
    # 0-indexed: lines 1100 to 1247 (lines[1099:1247])
    class_lines_full = lines[1099:1247]
    class_lines_init_forward = lines[1099:1198]
    
    return "".join(class_lines_full), "".join(class_lines_init_forward)


def main():
    server_kinonet_path = Path(__file__).resolve().parent / "kinonet_r2.py"
    with open(server_kinonet_path, "r", encoding="utf-8") as f:
        server_lines = f.readlines()

    # Find where 'class KinoNetR2' starts in kinonet_r2.py
    start_idx = None
    for i, line in enumerate(server_lines):
        if line.startswith("class KinoNetR2"):
            start_idx = i
            break
            
    assert start_idx is not None, "class KinoNetR2 not found in server/kinonet_r2.py"
    server_class_source = "".join(server_lines[start_idx:])

    orig_full, orig_init_forward = extract_original_kinonet_source()

    hash_server = hashlib.sha256(server_class_source.encode("utf-8")).hexdigest()
    hash_orig_full = hashlib.sha256(orig_full.encode("utf-8")).hexdigest()
    hash_orig_init_fwd = hashlib.sha256(orig_init_forward.encode("utf-8")).hexdigest()

    print("=" * 75)
    print("KINONET-R2 SOURCE HASH VERIFICATION REPORT")
    print("=" * 75)
    print(f"Server kinonet_r2.py KinoNetR2 class SHA-256 : {hash_server}")
    print(f"Original georeckon_pipeline.py (1100-1247)   : {hash_orig_full}")
    print(f"Original georeckon_pipeline.py (1100-1198)   : {hash_orig_init_fwd}")
    print("-" * 75)
    
    exact_match = (hash_server == hash_orig_full)
    print(f"Exact match with lines 1100-1247: {exact_match}")
    print("-" * 75)
    print("EXPLANATION OF 1100-1198 vs 1100-1247 RANGE:")
    print(" - Lines 1100-1198 contain: class KinoNetR2(nn.Module), __init__(), and forward().")
    print(" - Lines 1199-1247 contain: predict_with_uncertainty() method (Monte Carlo Dropout inference).")
    print(" - Therefore, 1100-1198 is the base model definition, while 1100-1247 is the complete")
    print("   class with MC Dropout epistemic & aleatoric uncertainty prediction.")
    print("=" * 75)


if __name__ == "__main__":
    main()
