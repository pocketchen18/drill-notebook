param(
    [string]$AppRoot = '',
    [switch]$CheckOnly,
    [switch]$Rebuild,
    [switch]$NoInstall,
    [int]$RemoteDebuggingPort = 0
)

$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-WorkspacePath {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return [System.IO.Path]::GetFullPath((Join-Path $workspace 'runtime-portable'))
    }
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $workspace $Value))
}

function Test-WithinRoot {
    param(
        [string]$Candidate,
        [string]$Parent
    )

    $parentPrefix = $Parent.TrimEnd('\') + '\'
    return $Candidate.Equals($Parent, [System.StringComparison]::OrdinalIgnoreCase) -or
        $Candidate.StartsWith($parentPrefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Require-Command {
    param(
        [string]$Name,
        [string]$Hint
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Hint"
    }
}

function Invoke-Checked {
    param(
        [string]$Command,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Write-Host (">> " + $Command + ' ' + ($Arguments -join ' ')) -ForegroundColor DarkGray
    $previousDirectory = (Get-Location).Path
    try {
        if ($WorkingDirectory) {
            Set-Location -LiteralPath $WorkingDirectory
        }
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $Command"
        }
    } finally {
        Set-Location -LiteralPath $previousDirectory
    }
}

function Find-BackendJar {
    $target = Join-Path $workspace 'backend\target'
    $preferred = Join-Path $target 'drill-notebook-backend-0.1.0.jar'
    if (Test-Path -LiteralPath $preferred) {
        return $preferred
    }
    if (-not (Test-Path -LiteralPath $target)) {
        return $null
    }
    $jar = Get-ChildItem -LiteralPath $target -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' } |
        Select-Object -First 1
    if ($jar) {
        return $jar.FullName
    }
    return $null
}

$root = Resolve-WorkspacePath $AppRoot
if (-not (Test-WithinRoot -Candidate $root -Parent $workspace)) {
    throw "AppRoot must remain inside the workspace: $workspace"
}

Require-Command 'node' 'Install Node.js 20 or newer, then rerun this script.'
Require-Command 'npm.cmd' 'Install Node.js 20 or newer, then rerun this script.'
Require-Command 'java' 'Install a JDK/JRE 17 or newer, then rerun this script.'

$nodeVersionText = (& node --version).Trim().TrimStart('v')
try {
    $nodeVersion = [System.Version]$nodeVersionText
} catch {
    throw "Could not parse Node.js version: $nodeVersionText"
}
if ($nodeVersion.Major -lt 20) {
    throw "Node.js 20 or newer is required. Found $nodeVersionText."
}

$electronExe = Join-Path $workspace 'node_modules\electron\dist\electron.exe'
$tscCommand = Join-Path $workspace 'node_modules\.bin\tsc.cmd'
$frontendEntry = Join-Path $workspace 'frontend\dist\index.html'
$electronEntry = Join-Path $workspace 'electron-dist\main.js'
$backendJar = Find-BackendJar

if (-not (Test-Path -LiteralPath $electronExe) -or -not (Test-Path -LiteralPath $tscCommand)) {
    if ($NoInstall) {
        throw 'Node dependencies are missing. Run npm install, or rerun without -NoInstall.'
    }
    Invoke-Checked 'npm.cmd' @('install', '--no-audit', '--no-fund') $workspace
}

function Test-Stale {
    param(
        [string[]]$SourcePaths,
        [string]$OutputFile
    )
    if (-not (Test-Path -LiteralPath $OutputFile)) { return $true }
    $outputTime = (Get-Item -LiteralPath $OutputFile).LastWriteTime
    foreach ($sourcePath in $SourcePaths) {
        if (-not (Test-Path -LiteralPath $sourcePath)) { continue }
        $item = Get-Item -LiteralPath $sourcePath
        if ($item.PSIsContainer) {
            $newest = Get-ChildItem -LiteralPath $sourcePath -Recurse -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($newest -and $newest.LastWriteTime -gt $outputTime) { return $true }
        } elseif ($item.LastWriteTime -gt $outputTime) {
            return $true
        }
    }
    return $false
}

# Rebuild when any source is newer than the compiled output, so a merge or an
# edit under electron/ or frontend/src is always picked up by a plain `npm start`
# (previously only a missing output triggered a rebuild, letting a stale
# electron-dist/main.js without new IPC handlers silently keep running).
$frontendStale = Test-Stale -SourcePaths @(
    (Join-Path $workspace 'frontend\src'),
    (Join-Path $workspace 'frontend\index.html'),
    (Join-Path $workspace 'frontend\vite.config.ts'),
    (Join-Path $workspace 'frontend\tsconfig.json')
) -OutputFile $frontendEntry
$electronStale = Test-Stale -SourcePaths @(
    (Join-Path $workspace 'electron')
) -OutputFile $electronEntry

$buildFrontendAndElectron = $Rebuild -or $frontendStale -or $electronStale
$buildBackend = $Rebuild -or [string]::IsNullOrWhiteSpace($backendJar)

if ($CheckOnly) {
    if ($buildFrontendAndElectron) {
        throw "Frontend/Electron build is missing. Run npm start or npm run build. Expected: $frontendEntry and $electronEntry"
    }
    if ($buildBackend) {
        throw "Backend jar is missing. Run npm start or npm run build:backend. Expected under backend\target"
    }
    Write-Host 'Drill Notebook startup check passed.' -ForegroundColor Green
    Write-Host "Workspace: $workspace"
    Write-Host "AppRoot:   $root"
    Write-Host "Frontend:  $frontendEntry"
    Write-Host "Electron:  $electronEntry"
    Write-Host "Backend:   $backendJar"
    Write-Host 'Bundled JRE is not required for development startup; the system Java command is used.'
    exit 0
}

function Stop-RunningApp {
    # Release file handles on backend/target and electron-dist before rebuilding.
    # If this app's java/electron processes are still running, on Windows they hold
    # locks on build outputs; a locked `clean package` can emit a jar that is missing
    # classpath resources (e.g. schema.sql) yet still print BUILD SUCCESS, which then
    # fails at startup with "schema.sql ... does not exist". Only this app's own
    # processes are stopped (matched by jar name / bundled electron path) — never
    # unrelated java or electron processes (e.g. the IDE).
    $electronPrefix = (Join-Path $workspace 'node_modules\electron') + '\'
    foreach ($p in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) {
        $isBackend = $p.CommandLine -and $p.CommandLine -like '*drill-notebook-backend*'
        $isAppShell = $p.ExecutablePath -and $p.ExecutablePath.StartsWith($electronPrefix, [System.StringComparison]::OrdinalIgnoreCase)
        if ($isBackend -or $isAppShell) {
            Write-Host "Stopping running Drill Notebook process (PID $($p.ProcessId)) before rebuild" -ForegroundColor DarkGray
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
}

if ($buildFrontendAndElectron -or $buildBackend) {
    Stop-RunningApp
    Start-Sleep -Seconds 1
}

if ($buildFrontendAndElectron) {
    Invoke-Checked 'npm.cmd' @('run', 'build') $workspace
}
if ($buildBackend) {
    $mavenWrapper = Join-Path $workspace 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "Maven wrapper not found: $mavenWrapper"
    }
    # Use the workspace-local Maven repo and a writable temp dir so the build
    # is reproducible regardless of the caller's environment.
    $toolingTmp = Join-Path $workspace '.tooling\tmp'
    New-Item -ItemType Directory -Force -Path $toolingTmp | Out-Null
    $oldTmp = $env:TMP
    $oldTemp = $env:TEMP
    $env:TMP = $toolingTmp
    $env:TEMP = $toolingTmp
    try {
        Invoke-Checked $mavenWrapper @('-f', 'backend/pom.xml', 'clean', 'package', '-DskipTests', '-Dmaven.repo.local=.tooling/m2') $workspace
    } finally {
        $env:TMP = $oldTmp
        $env:TEMP = $oldTemp
    }
}

if (-not (Test-Path -LiteralPath $electronExe)) {
    throw "Electron executable is missing after dependency setup: $electronExe"
}
if (-not (Test-Path -LiteralPath $frontendEntry) -or -not (Test-Path -LiteralPath $electronEntry)) {
    throw 'Build completed without producing the Electron or frontend entrypoint.'
}
if ([string]::IsNullOrWhiteSpace((Find-BackendJar))) {
    throw 'Build completed without producing a backend jar.'
}

New-Item -ItemType Directory -Force -Path $root | Out-Null
$oldAppRoot = $env:APP_ROOT
$oldWorkerExe = $env:DRILL_EMBEDDING_WORKER_EXE
$oldElectronRunAsNode = $env:ELECTRON_RUN_AS_NODE
$env:APP_ROOT = $root
# Embedding worker path: workspace-release binary (not compiled automatically).
$env:DRILL_EMBEDDING_WORKER_EXE = Join-Path $workspace 'embedding-worker\target\x86_64-pc-windows-msvc\release\embedding-worker.exe'
# Some shells/tools export ELECTRON_RUN_AS_NODE=1, which makes Electron behave as plain Node
# and breaks require('electron').app. Clear it for the app process only.
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
try {
    Write-Host "Starting Drill Notebook with APP_ROOT=$root" -ForegroundColor Cyan
    Write-Host 'Close the Electron window to stop the local Java backend.' -ForegroundColor DarkGray
    $electronArguments = @($workspace)
    if ($RemoteDebuggingPort -gt 0) { $electronArguments = @("--remote-debugging-port=$RemoteDebuggingPort", $workspace) }
    & $electronExe @electronArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Electron exited with code $LASTEXITCODE"
    }
} finally {
    $env:APP_ROOT = $oldAppRoot
    $env:DRILL_EMBEDDING_WORKER_EXE = $oldWorkerExe
    if ($null -eq $oldElectronRunAsNode) {
        Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
    } else {
        $env:ELECTRON_RUN_AS_NODE = $oldElectronRunAsNode
    }
}
