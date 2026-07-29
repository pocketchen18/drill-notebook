<#
.SYNOPSIS
    Build the embedding-worker Rust crate with workspace-private toolchain.
.DESCRIPTION
    Uses workspace-local RUSTUP_HOME/CARGO_HOME/CARGO_TARGET_DIR so that
    the global Rust installation is never modified.  Only reads/executes
    the global rustup binary, MSVC Build Tools and Windows SDK.
#>

$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$CrateDir       = Join-Path $WorkspaceRoot "embedding-worker"
$ToolingDir     = Join-Path $WorkspaceRoot ".tooling"
$TargetDir      = Join-Path $CrateDir "target"

# ---- set workspace-private environment ----
$env:RUSTUP_HOME     = Join-Path $ToolingDir "rustup"
$env:CARGO_HOME      = Join-Path $ToolingDir "cargo"
$env:CARGO_TARGET_DIR = $TargetDir
$env:TEMP             = Join-Path $ToolingDir "tmp"
$env:TMP              = Join-Path $ToolingDir "tmp"

# Ensure directories exist
@($env:RUSTUP_HOME, $env:CARGO_HOME, $env:TEMP) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

Write-Host "[build-embedding-worker] RUSTUP_HOME     = $env:RUSTUP_HOME"
Write-Host "[build-embedding-worker] CARGO_HOME      = $env:CARGO_HOME"
Write-Host "[build-embedding-worker] CARGO_TARGET_DIR = $env:CARGO_TARGET_DIR"
Write-Host "[build-embedding-worker] Working dir     = $CrateDir"

# ---- build ----
Write-Host "[build-embedding-worker] Building embedding-worker (release, x86_64-pc-windows-msvc)..."
# Use --manifest-path so cargo finds the correct Cargo.toml regardless of cwd
rustup run 1.88.0 cargo build --release --target x86_64-pc-windows-msvc --manifest-path (Join-Path $CrateDir "Cargo.toml")
if (-not $?) {
    Write-Host "[build-embedding-worker] BUILD FAILED"
    exit 1
}

$exe = Join-Path (Join-Path $TargetDir "x86_64-pc-windows-msvc") "release\embedding-worker.exe"
if (Test-Path $exe) {
    Write-Host "[build-embedding-worker] BUILD OK: $exe"
    exit 0
}
else {
    Write-Host "[build-embedding-worker] BUILD FAILED: binary not found at $exe"
    exit 1
}
