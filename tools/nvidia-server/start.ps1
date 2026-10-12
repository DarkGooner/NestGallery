# Starts the NestGallery face endpoint. Run setup.cmd once first.
#   start.cmd                 phone on USB: the endpoint listens on this PC only; the app uses http://localhost:8765
#                             after "adb reverse tcp:8765 tcp:8765"
#   start.cmd -Lan            also reachable from the phone over the same Wi-Fi (Windows asks to allow it in the firewall)
#   start.cmd -Token abc123   keep the same token across restarts (default: a new random one each start, never saved)
param([switch]$Lan, [int]$Port = 8765, [string]$Token = "")
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Py = Join-Path $Root ".venv\Scripts\python.exe"
if (-not (Test-Path $Py)) { Write-Host "Run setup.cmd first." -ForegroundColor Red; exit 1 }

$env:PYTHONDONTWRITEBYTECODE = "1"      # nothing written next to the scripts
$env:PYTHONNOUSERSITE = "1"             # ignore packages from a system-wide Python

$pyArgs = @((Join-Path $Root "server.py"), "--port", $Port)
if ($Lan) { $pyArgs += @("--host", "0.0.0.0") }
if ($Token) { $pyArgs += @("--token", $Token) }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not $Lan -and (Test-Path $adb)) {
    # USB: make the phone's localhost:PORT reach this PC (lasts until the phone is unplugged)
    & $adb reverse "tcp:$Port" "tcp:$Port" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "adb reverse set up: the phone's localhost:$Port now reaches this PC." }
}

& $Py -B @args
