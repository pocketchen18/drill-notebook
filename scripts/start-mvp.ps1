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
        [string[]]$Arguments
    )

    Write-Host (">> " + $Command + ' ' + ($Arguments -join ' ')) -ForegroundColor DarkGray
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $Command"
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
    Invoke-Checked 'npm.cmd' @('install', '--no-audit', '--no-fund')
}

$buildFrontendAndElectron = $Rebuild -or
    -not (Test-Path -LiteralPath $frontendEntry) -or
    -not (Test-Path -LiteralPath $electronEntry)
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

if ($buildFrontendAndElectron) {
    Invoke-Checked 'npm.cmd' @('run', 'build')
}
if ($buildBackend) {
    $mavenWrapper = Join-Path $workspace 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "Maven wrapper not found: $mavenWrapper"
    }
    Invoke-Checked $mavenWrapper @('-f', 'backend/pom.xml', '-DskipTests', 'package')
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
$env:APP_ROOT = $root
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
}
