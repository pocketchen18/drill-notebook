#requires -Version 7.0
param([Parameter(Mandatory)][int]$ProcessId, [Parameter(Mandatory)][string]$AppRoot)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$root = [IO.Path]::GetFullPath($AppRoot)
if (-not $root.StartsWith((Join-Path $workspace 'temp') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only an isolated workspace test application may be stopped.'
}
$process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if ($process -and $process.Path -and $process.Path.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    # Compare the Unicode path in PowerShell itself; console encoding must not decide process ownership.
    & taskkill.exe /PID $ProcessId /T /F
}
