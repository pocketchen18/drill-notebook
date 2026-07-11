param(
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

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

$jreJava = Join-Path $workspace 'jre\bin\java.exe'
$electronBuilder = Join-Path $workspace 'node_modules\.bin\electron-builder.cmd'
$frontendEntry = Join-Path $workspace 'frontend\dist\index.html'
$electronEntry = Join-Path $workspace 'electron-dist\main.js'
$backendJar = Join-Path $workspace 'backend\target\drill-notebook-backend-0.1.0.jar'

if (-not (Test-Path -LiteralPath $jreJava)) {
    throw "Embedded JRE is missing: $jreJava. Create it with the recipe in docs\jlink.md before packaging."
}
if (-not (Test-Path -LiteralPath $electronBuilder)) {
    throw 'electron-builder is missing. Run npm install first.'
}

$needsAppBuild = $Rebuild -or
    -not (Test-Path -LiteralPath $frontendEntry) -or
    -not (Test-Path -LiteralPath $electronEntry)
if ($needsAppBuild) {
    Invoke-Checked 'npm.cmd' @('run', 'build')
}

$needsBackendBuild = $Rebuild -or -not (Test-Path -LiteralPath $backendJar)
if ($needsBackendBuild) {
    Invoke-Checked (Join-Path $workspace 'mvnw.cmd') @('-f', 'backend/pom.xml', '-DskipTests', 'package')
}

if (-not (Test-Path -LiteralPath $backendJar)) {
    throw "Backend jar is missing after build: $backendJar"
}

Invoke-Checked $electronBuilder @('--win', 'portable')

$artifacts = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'dist') -Filter '*.exe' -File -ErrorAction SilentlyContinue)
if ($artifacts.Count -eq 0) {
    throw 'electron-builder completed but no portable .exe was found under dist.'
}

Write-Host 'Portable package created:' -ForegroundColor Green
$artifacts | ForEach-Object { Write-Host $_.FullName }
