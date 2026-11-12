#requires -Version 7.0
param([switch]$Test)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$crate = Join-Path $workspace 'updater'
$saved = @{}
foreach ($name in @('CARGO_HOME', 'CARGO_TARGET_DIR', 'TEMP', 'TMP', 'TMPDIR')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    # Reuse the installed toolchain. Only this small helper's dependencies and outputs are workspace-local.
    $env:CARGO_HOME = Join-Path $workspace '.tooling\cargo'
    $env:CARGO_TARGET_DIR = Join-Path $crate 'target'
    $env:TEMP = Join-Path $workspace 'temp\updater-toolchain'
    $env:TMP = $env:TEMP
    $env:TMPDIR = $env:TEMP
    foreach ($directory in @($env:CARGO_HOME, $env:TEMP)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    $arguments = @('build', '--release', '--manifest-path', (Join-Path $crate 'Cargo.toml'))
    if (Test-Path -LiteralPath (Join-Path $crate 'Cargo.lock')) { $arguments += '--locked' }
    & cargo @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Portable updater compilation failed.' }
    if ($Test) {
        & cargo test --locked --manifest-path (Join-Path $crate 'Cargo.toml')
        if ($LASTEXITCODE -ne 0) { throw 'Portable updater tests failed.' }
    }
    $exe = Join-Path $crate 'target\release\drill-updater.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "Portable updater is missing: $exe" }
    Write-Host "Portable updater built: $exe"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
