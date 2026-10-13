# NestGallery face endpoint - one-time setup on a Windows PC with an NVIDIA GPU.
#
# Everything goes into THIS folder: a private Python (via uv), its packages (ONNX Runtime GPU, CUDA / cuDNN runtime
# libraries from pip), the download cache and the model. Nothing is installed system-wide, PATH and the registry are
# not touched, and no system CUDA toolkit is needed (only the normal NVIDIA driver). To uninstall, delete the folder.
#
# Pinned for driver 566 / CUDA 12.7 (RTX 4060 laptop): onnxruntime-gpu 1.22 + cuDNN 9.10. Newer onnxruntime-gpu
# wheels need CUDA 13 (error 801) and newer cuDNN failed on that driver.
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Runtime = Join-Path $Root "runtime"
$Models = Join-Path $Root "models"
$Venv = Join-Path $Root ".venv"
$Py = Join-Path $Venv "Scripts\python.exe"

$UvVersion = "0.6.14"
# Model files from a fixed commit: the GPU graph reads its weights as byte ranges of the int8 file, so the two must
# be exactly this pair (checked by SHA-256).
$Commit = "558c1d9df90e1363233cbab001bde0e633aef778"
$ModelFiles = @{
    "adaface_ir101_gpu.onnx"  = "a0eb1183551262ccf1f863ae7a4c13b5e3ec46399f87a00bb99e8b6eca698d9f"
    "adaface_ir101_int8.onnx" = "d64ee585980304db6a974621ab7c684f360639bb80da6adacdb0b7012402fe1b"
    "scrfd_500m_640.onnx"     = "98cb7b00f99874543cde21b4f63a960b4d2f63bbfa7f68cc36381de817e93673"
}

# Keep every tool's downloads and caches inside this folder.
$env:UV_CACHE_DIR = Join-Path $Runtime "cache"
$env:UV_PYTHON_INSTALL_DIR = Join-Path $Runtime "python"
$env:UV_PYTHON_BIN_DIR = Join-Path $Runtime "python-bin"
$env:UV_PYTHON_PREFERENCE = "only-managed"
$env:UV_NO_CONFIG = "1"
$env:UV_HTTP_TIMEOUT = "600"            # the CUDA wheels are hundreds of MB; uv's default 30 s timed out on them
$env:PIP_NO_CACHE_DIR = "1"
$env:PYTHONDONTWRITEBYTECODE = "1"

function Download($url, $out) {
    # curl.exe ships with Windows 11. --ssl-no-revoke: the revocation check fails on some networks; -C - resumes.
    & curl.exe --ssl-no-revoke -L --fail --retry 5 -C - -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
}

Write-Host "== NestGallery face endpoint setup ==" -ForegroundColor Cyan
Write-Host "Installing into $Root"

$smi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if ($smi) {
    & nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader
} else {
    Write-Warning "nvidia-smi not found: is the NVIDIA driver installed? Setup continues, but the GPU may not load."
}

New-Item -ItemType Directory -Force -Path $Runtime, $Models | Out-Null

# 1. uv (a single exe that fetches a private Python and installs packages)
$Uv = Join-Path $Runtime "uv\uv.exe"
if (-not (Test-Path $Uv)) {
    Write-Host "`n[1/4] Downloading uv $UvVersion"
    $zip = Join-Path $Runtime "uv.zip"
    Download "https://github.com/astral-sh/uv/releases/download/$UvVersion/uv-x86_64-pc-windows-msvc.zip" $zip
    Expand-Archive -Force $zip (Join-Path $Runtime "uv")
    Remove-Item $zip
} else { Write-Host "`n[1/4] uv already here" }

# 2. Python 3.12 + a virtual environment, both inside this folder
Write-Host "`n[2/4] Python 3.12 environment"
if (-not (Test-Path $Py)) {
    & $Uv venv $Venv --python 3.12
    if ($LASTEXITCODE -ne 0) { throw "Could not create the Python environment" }
}

# 3. ONNX Runtime GPU + the CUDA 12 / cuDNN 9 runtime libraries (pip wheels, no CUDA toolkit install); Pillow decodes
#    the photos the phone sends, onnx builds the float16 recogniser
Write-Host "`n[3/4] ONNX Runtime GPU, CUDA and cuDNN libraries (about 1.5 GB, first time only)"
& $Uv pip install --python $Py "onnxruntime-gpu[cuda,cudnn]==1.22.0" "nvidia-cudnn-cu12==9.10.2.21" "numpy<3" "pillow==11.2.1" "onnx==1.17.0"
if ($LASTEXITCODE -ne 0) { throw "Package install failed" }

# 4. The model: copied from this repository if the script runs inside it, else downloaded from GitHub
Write-Host "`n[4/4] Face models"
$RepoAssets = Join-Path $Root "..\..\app\src\main\assets"
foreach ($name in $ModelFiles.Keys) {
    $dst = Join-Path $Models $name
    $want = $ModelFiles[$name]
    if ((Test-Path $dst) -and ((Get-FileHash $dst -Algorithm SHA256).Hash.ToLower() -eq $want)) { continue }
    $src = Join-Path $RepoAssets $name
    if ((Test-Path $src) -and ((Get-FileHash $src -Algorithm SHA256).Hash.ToLower() -eq $want)) {
        Copy-Item $src $dst -Force
    } else {
        Download "https://raw.githubusercontent.com/DarkGooner/NestGallery/$Commit/app/src/main/assets/$name" $dst
    }
    $got = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $want) { Remove-Item $dst; throw "$name has the wrong checksum ($got)" }
}

# The float16 recogniser (same weights; ~1.9x faster on the GPU). server.py checks it against the float32 graph.
Write-Host "`nBuilding the float16 recogniser"
& $Py -B (Join-Path $Root "make_fp16.py") $Models
if ($LASTEXITCODE -ne 0) { Write-Warning "Could not build the float16 recogniser; the server will use float32 (slower)." }

# Check that the GPU really loads (ONNX Runtime falls back to the CPU silently otherwise).
Write-Host "`nChecking the GPU..."
& $Py -B -c "import sys; sys.path.insert(0, r'$Root'); import server; s, p = server.load_session(False); print('OK:', p)"
if ($LASTEXITCODE -ne 0) { throw "The GPU check failed (see the message above)." }

Write-Host "`nSetup done. Start the endpoint with start.cmd (USB) or start.cmd -Lan (also Wi-Fi)." -ForegroundColor Green
