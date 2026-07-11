param(
    [string]$AppRoot = '',
    [switch]$RunSmoke
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($AppRoot)) {
    $root = [System.IO.Path]::GetFullPath((Join-Path $workspace 'runtime-portable-audit'))
} elseif ([System.IO.Path]::IsPathRooted($AppRoot)) {
    $root = [System.IO.Path]::GetFullPath($AppRoot)
} else {
    $root = [System.IO.Path]::GetFullPath((Join-Path $workspace $AppRoot))
}
if (-not $root.Equals($workspace, [System.StringComparison]::OrdinalIgnoreCase) -and
    -not $root.StartsWith($workspace.TrimEnd('\') + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Audit AppRoot must stay inside the workspace: $workspace"
}
$evidence = Join-Path $workspace '.omo\evidence\task-13-portable-audit.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $evidence) | Out-Null

function Get-ForbiddenSnapshot {
    $targets = @(
        (Join-Path $env:APPDATA 'Drill Notebook'),
        (Join-Path $env:LOCALAPPDATA 'Drill Notebook'),
        (Join-Path $env:USERPROFILE '.drill-notebook'),
        (Join-Path $env:USERPROFILE '.drill*')
    )
    $items = @()
    foreach ($target in $targets) {
        $parent = Split-Path $target -Parent
        $leaf = Split-Path $target -Leaf
        if (Test-Path $parent) {
            $items += Get-ChildItem -LiteralPath $parent -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -like $leaf } | ForEach-Object {
                if ($_.PSIsContainer) { Get-ChildItem -LiteralPath $_.FullName -Recurse -Force -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName } else { $_.FullName }
            }
        }
    }
    return @($items | Sort-Object -Unique)
}

$before = Get-ForbiddenSnapshot
$runExit = 0
try {
    if ($RunSmoke) {
        & (Join-Path $PSScriptRoot 'smoke-mvp.ps1') -AppRoot $root
        $runExit = $LASTEXITCODE
    }
} catch {
    $runExit = 1
    Add-Content -LiteralPath $evidence -Value "Smoke error: $($_.Exception.Message)"
}
$after = Get-ForbiddenSnapshot
$newFiles = @($after | Where-Object { $_ -notin $before })

$lines = @(
    "AppRoot: $root",
    "Before forbidden files: $($before.Count)",
    "After forbidden files: $($after.Count)",
    "Smoke exit: $runExit",
    "New forbidden files: $($newFiles.Count)"
)
if ($newFiles.Count) { $lines += $newFiles }
$lines | Set-Content -LiteralPath $evidence -Encoding utf8

if ($runExit -ne 0 -or $newFiles.Count -gt 0) { exit 1 }
Write-Output "Portable audit passed. Evidence: $evidence"
