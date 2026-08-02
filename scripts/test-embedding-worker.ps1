<#
.SYNOPSIS
    Run embedding-worker Rust tests with workspace-private toolchain.
.DESCRIPTION
    Runs `cargo test` (both unit and integration tests) using workspace-local
    RUSTUP_HOME/CARGO_HOME/CARGO_TARGET_DIR.
.PARAMETER NoParity
    Explicitly skip the parity integration tests (offline_golden_parity,
    query_prefix_added_correctly, deterministic_geometry) by filter name.
    This mode is for development only; Task 1 acceptance/evidence MUST use
    the full test suite (without -NoParity).
#>

param(
    [switch]$NoParity
)

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

Write-Host "[test-embedding-worker] RUSTUP_HOME     = $env:RUSTUP_HOME"
Write-Host "[test-embedding-worker] CARGO_HOME      = $env:CARGO_HOME"
Write-Host "[test-embedding-worker] CARGO_TARGET_DIR = $env:CARGO_TARGET_DIR"

# ---- check fixture ----
$FixtureDir = Join-Path (Join-Path (Join-Path (Join-Path $WorkspaceRoot "runtime-portable") "data") "models") "embedding-fixtures"
$RequiredFixtureFiles = @(
    "model_optimized.onnx",
    "tokenizer.json",
    "config.json",
    "tokenizer_config.json",
    "special_tokens_map.json"
)
$hasFixture = $true
foreach ($f in $RequiredFixtureFiles) {
    if (-not (Test-Path (Join-Path $FixtureDir $f))) {
        $hasFixture = $false
        break
    }
}

if ($hasFixture) {
    $env:EMBEDDING_FIXTURE_DIR = $FixtureDir
    Write-Host "[test-embedding-worker] Fixture found at $FixtureDir"
    Write-Host "[test-embedding-worker] Parity tests will run."
}
else {
    Write-Host "[test-embedding-worker] WARNING: Fixture not found at $FixtureDir"
    if ($NoParity) {
        Write-Host "[test-embedding-worker] -NoParity active: parity tests will be skipped via --skip filter."
    } else {
        Write-Host "[test-embedding-worker] Parity tests will FAIL (hard gate). Run 'scripts/fetch-embedding-fixture.ps1' first."
        # Do NOT set EMBEDDING_FIXTURE_DIR — parity tests will panic
    }
}

# ---- build skip filters for -NoParity ----
$skipArgs = @()
if ($NoParity) {
    $skipArgs += "--skip"
    $skipArgs += "offline_golden_parity"
    $skipArgs += "--skip"
    $skipArgs += "query_prefix_added_correctly"
    $skipArgs += "--skip"
    $skipArgs += "deterministic_geometry"
}

# ---- run tests ----
Write-Host "[test-embedding-worker] Running cargo test (release)..."
$cargoArgs = @(
    "test", "--release", "--target", "x86_64-pc-windows-msvc",
    "--manifest-path", (Join-Path $CrateDir "Cargo.toml")
)
if ($skipArgs.Count -gt 0) {
    $cargoArgs += "--"
    $cargoArgs += $skipArgs
}
rustup run 1.88.0 cargo $cargoArgs
if (-not $?) {
    Write-Host "[test-embedding-worker] TESTS FAILED"
    exit 1
}

Write-Host "[test-embedding-worker] ALL TESTS PASSED"
exit 0
