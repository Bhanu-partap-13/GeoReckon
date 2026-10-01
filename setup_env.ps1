# setup_env.ps1 — One-command environment setup for GeoReckon WebSocket server
# Run from the project root: .\setup_env.ps1
# Requires: Python 3.14+ already on PATH, internet access

$ErrorActionPreference = "Stop"

Write-Host "=== GeoReckon Server Environment Setup ===" -ForegroundColor Cyan

# 1. Create venv if it doesn't exist
if (-not (Test-Path ".venv")) {
    Write-Host "[1/4] Creating virtual environment..." -ForegroundColor Yellow
    python -m venv .venv
} else {
    Write-Host "[1/4] Virtual environment already exists." -ForegroundColor Green
}

# Activate
$activate = ".\.venv\Scripts\Activate.ps1"
. $activate
Write-Host "      Activated: $((Get-Command python).Source)" -ForegroundColor Green

# 2. Install PyTorch CPU (numpy-independent, must come first)
Write-Host "[2/4] Installing PyTorch 2.14.0 CPU..." -ForegroundColor Yellow
python -m pip install --quiet torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
python -c "import torch; print('      torch', torch.__version__)" 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: torch install failed" -ForegroundColor Red; exit 1 }

# 3. Install server dependencies (binary-only to avoid GCC 6 source-build failure)
Write-Host "[3/4] Installing server dependencies (binary wheels only)..." -ForegroundColor Yellow
python -m pip install --quiet --only-binary=:all: `
    "numpy>=2.3" `
    "scipy>=1.14" `
    "pandas>=2.2" `
    "matplotlib>=3.9" `
    "reportlab>=4.2" `
    "pypdfium2>=4.30" `
    flask==3.1.1 `
    flask-socketio==5.5.1 `
    flask-cors==5.0.1 `
    simple-websocket==1.1.0 `
    "python-socketio[client]==5.12.1" `
    websocket-client==1.8.0

# 4. Verify
Write-Host "[4/4] Verifying installs..." -ForegroundColor Yellow
python -c @"
import torch, numpy, scipy, pandas, flask, flask_socketio, simple_websocket, socketio
print('  torch            ', torch.__version__)
print('  numpy            ', numpy.__version__)
print('  scipy            ', scipy.__version__)
print('  pandas           ', pandas.__version__)
print('  flask            ', flask.__version__)
print('  flask_socketio   ', flask_socketio.__version__)
print('  simple_websocket  OK')
print('  python-socketio  ', socketio.__version__)
"@

Write-Host "`n=== Setup complete! ===" -ForegroundColor Green
Write-Host "Run tests:  python -m unittest discover -s georeckon/tests" -ForegroundColor Cyan
Write-Host "Run server: python -m georeckon.server.app" -ForegroundColor Cyan
