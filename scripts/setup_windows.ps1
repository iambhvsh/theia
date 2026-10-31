# ==============================================================================
# Theia Model & Engine Setup for Windows (PowerShell)
# Fetches the Needle engine (android-arm64) and Whistle + Needle 3 models.
# ==============================================================================
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path "$ScriptDir\..").Path
Set-Location $RootDir

Write-Host "==> Setting up Theia on Windows..." -ForegroundColor Cyan

# Locate Python
$Python = $null
if (Get-Command python -ErrorAction SilentlyContinue) {
    $Python = "python"
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    $Python = "py -3"
} else {
    Write-Error "Python 3 is required. Please install Python 3 from https://python.org or via 'winget install Python.Python.3.11' and ensure it is added to PATH."
    exit 1
}

$VenvDir = Join-Path $RootDir ".venv"
$VenvNeedle = Join-Path $VenvDir "Scripts\needle.exe"
$VenvPip = Join-Path $VenvDir "Scripts\pip.exe"

$Needle = $null
if (Get-Command needle -ErrorAction SilentlyContinue) {
    $Needle = "needle"
    Write-Host "Found system needle CLI" -ForegroundColor Green
} elseif (Test-Path $VenvNeedle) {
    $Needle = $VenvNeedle
    Write-Host "Found needle CLI in $VenvDir" -ForegroundColor Green
} else {
    Write-Host "Creating virtual environment in $VenvDir..." -ForegroundColor Yellow
    Invoke-Expression "$Python -m venv `"$VenvDir`""
    & $VenvPip install --upgrade cactus-needle
    $Needle = $VenvNeedle
}

# Download models and engine
Write-Host "==> Fetching engine and models via needle..." -ForegroundColor Cyan
$FetchDir = Join-Path $RootDir ".fetch"
if (Test-Path $FetchDir) {
    Remove-Item -Recurse -Force $FetchDir
}
New-Item -ItemType Directory -Force -Path $FetchDir | Out-Null
Set-Location $FetchDir

& $Needle download android-arm64
& $Needle download whistle
& $Needle download needle3

Set-Location $RootDir

# Locate downloaded files
$Lib = Get-ChildItem -Path $FetchDir -Recurse -Filter "libneedle.a" | Select-Object -First 1
$Hdr = Get-ChildItem -Path $FetchDir -Recurse -Filter "needle.h" | Select-Object -First 1

$CacheDir = Join-Path $env:USERPROFILE ".cache"
$SearchDirs = @($FetchDir)
if (Test-Path $CacheDir) { $SearchDirs += $CacheDir }

$W = Get-ChildItem -Path $SearchDirs -Recurse -Filter "whistle.cact" -ErrorAction SilentlyContinue | Select-Object -First 1
$N = Get-ChildItem -Path $SearchDirs -Recurse -Filter "needle3.cact" -ErrorAction SilentlyContinue | Select-Object -First 1

if (-not $Lib -or -not $Hdr -or -not $W -or -not $N) {
    Write-Error "Missing one or more required artifacts: Lib=$Lib, Hdr=$Hdr, Whistle=$W, Needle3=$N"
    exit 1
}

# Copy artifacts to target folders
$EngineDir = Join-Path $RootDir "app\src\main\cpp\engine"
$AssetsDir = Join-Path $RootDir "app\src\main\assets"

New-Item -ItemType Directory -Force -Path $EngineDir | Out-Null
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null

Copy-Item $Lib.FullName -Destination $EngineDir -Force
Copy-Item $Hdr.FullName -Destination $EngineDir -Force
Copy-Item $W.FullName -Destination $AssetsDir -Force
Copy-Item $N.FullName -Destination $AssetsDir -Force

Write-Host "==> Windows setup complete!" -ForegroundColor Green
Get-ChildItem $EngineDir
Get-ChildItem $AssetsDir

