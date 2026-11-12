#requires -Version 7.0
param(
    [string]$AppRoot = '',
    [switch]$RunSmoke,
    [string]$RunScript = '',
    [string[]]$RunArguments = @()
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
$auditDirectory = Join-Path $workspace ('temp\portable-audit-' + [guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($auditDirectory) | Out-Null
$evidence = Join-Path $auditDirectory 'portable-audit.txt'
$evidenceRag = Join-Path $auditDirectory 'rag-paths.txt'

# APP_ROOT 内允许的运行数据（RAG 产物只允许出现在这里）
$AppRootAllowed = @('data\models\embeddings', 'data\study.db', 'cache', 'runtime', 'logs')
# 工作区源码树额外允许的 Cargo 构建产物
$WorkspaceAllowed = @('embedding-worker\target', 'updater\target')

function Get-FileFingerprint([System.IO.FileInfo]$file) {
    $hash = ''
    # Outside the workspace, use names/sizes/timestamps only; never open user files or credentials.
    if ($file.Length -le 10MB -and $file.FullName.StartsWith($workspace.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        try { $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash } catch { $hash = 'hash-error' }
    }
    return "$($file.Length)|$($file.LastWriteTimeUtc.Ticks)|$hash"
}

# 递归快照固定的 RAG 受监控根目录（均在 APP_ROOT 之外）。
function Get-RagMonitoredSnapshot {
    $roots = @(
        (Join-Path $env:USERPROFILE '.cache\huggingface'),
        (Join-Path $env:USERPROFILE '.cache\fastembed'),
        (Join-Path $env:USERPROFILE '.fastembed_cache'),
        (Join-Path $env:LOCALAPPDATA 'Drill Notebook'),
        (Join-Path $env:APPDATA 'Drill Notebook')
    )
    $entries = @{}
    foreach ($r in $roots) {
        if (Test-Path $r) {
            Get-ChildItem -LiteralPath $r -Recurse -Force -File -ErrorAction SilentlyContinue | ForEach-Object {
                $entries[$_.FullName] = Get-FileFingerprint $_
            }
        }
    }
    # 原始 %TEMP%（用户级，不受本进程覆盖影响）下以 fastembed/ort/drill-notebook 命名的条目
    $userTemp = [Environment]::GetEnvironmentVariable('TEMP', 'User')
    $tempRoots = @($env:TEMP, $userTemp) | Where-Object { $_ } | Select-Object -Unique
    foreach ($t in $tempRoots) {
        if (Test-Path $t) {
            Get-ChildItem -LiteralPath $t -Force -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -like 'fastembed*' -or $_.Name -like 'ort*' -or $_.Name -like 'drill-notebook*' -or $_.Name -like 'tomcat*'
            } | ForEach-Object {
                if ($_.PSIsContainer) {
                    Get-ChildItem -LiteralPath $_.FullName -Recurse -Force -File -ErrorAction SilentlyContinue | ForEach-Object {
                        $entries[$_.FullName] = Get-FileFingerprint $_
                    }
                } else {
                    $entries[$_.FullName] = Get-FileFingerprint $_
                }
            }
        }
    }
    return $entries
}

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
$ragBefore = Get-RagMonitoredSnapshot
$runExit = 0
try {
    if (-not [string]::IsNullOrWhiteSpace($RunScript)) {
        $scriptPath = [IO.Path]::GetFullPath((Join-Path $workspace $RunScript))
        if (-not $scriptPath.StartsWith($workspace.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'The audit target must stay inside the workspace.' }
        if ([IO.Path]::GetExtension($scriptPath) -eq '.mjs') { & node $scriptPath @RunArguments }
        elseif ([IO.Path]::GetExtension($scriptPath) -eq '.ps1') { & pwsh -NoProfile -File $scriptPath @RunArguments }
        else { throw 'The audit target must be a local .mjs or .ps1 script.' }
        $runExit = $LASTEXITCODE
    }
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

$ragAfter = Get-RagMonitoredSnapshot
$ragNewOrChanged = @($ragAfter.Keys | Where-Object {
    -not $ragBefore.ContainsKey($_) -or $ragBefore[$_] -ne $ragAfter[$_]
})

$lines = @(
    "AppRoot: $root",
    "Before forbidden files: $($before.Count)",
    "After forbidden files: $($after.Count)",
    "Smoke exit: $runExit",
    "New forbidden files: $($newFiles.Count)"
)
if ($newFiles.Count) { $lines += @($newFiles | Select-Object -First 20) }
$lines | Set-Content -LiteralPath $evidence -Encoding utf8

$ragLines = @(
    "AppRoot: $root",
    "AppRoot allowed runtime data: $($AppRootAllowed -join ', ')",
    "Workspace allowed build artifact: $($WorkspaceAllowed -join ', ')",
    "RAG monitored roots (metadata only): %USERPROFILE%\.cache\huggingface, %USERPROFILE%\.cache\fastembed, %USERPROFILE%\.fastembed_cache, %LOCALAPPDATA%\Drill Notebook, %APPDATA%\Drill Notebook, %TEMP%\{fastembed,ort,drill-notebook,tomcat}*",
    "Before monitored files: $($ragBefore.Count)",
    "After monitored files: $($ragAfter.Count)",
    "Smoke exit: $runExit",
    "New/changed monitored files outside APP_ROOT: $($ragNewOrChanged.Count)"
)
if ($ragNewOrChanged.Count) { $ragLines += @($ragNewOrChanged | Select-Object -First 20) }
$ragLines | Set-Content -LiteralPath $evidenceRag -Encoding utf8

if ($runExit -ne 0 -or $newFiles.Count -gt 0 -or $ragNewOrChanged.Count -gt 0) { exit 1 }
Write-Output "Portable audit passed. Evidence: $evidence, $evidenceRag"
