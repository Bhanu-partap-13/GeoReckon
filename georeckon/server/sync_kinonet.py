from pathlib import Path

pipe_path = Path(__file__).resolve().parent.parent.parent / "InertiaX" / "georeckon_pipeline.py"
with open(pipe_path, "r", encoding="utf-8") as f:
    lines = f.readlines()

header = '''"""
GeoReckon KinoNet-R2 AI Model Architecture
Verbatim extraction of KinoNetR2 from InertiaX/georeckon_pipeline.py (lines 1100-1247).
Used in strict shadow mode for speed and variance inference.
"""

from typing import Tuple
import torch
import torch.nn as nn
import torch.nn.functional as F


class Config:
    INPUT_CHANNELS = 6
    HIDDEN_DIM = 128
    DROPOUT_RATE = 0.2


'''
class_content = "".join(lines[1099:1247])
out_path = Path(__file__).resolve().parent / "kinonet_r2.py"
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write(header + class_content)

print("Synchronized kinonet_r2.py successfully.")
