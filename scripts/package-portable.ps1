#requires -Version 7.0

param(
    [switch]$Rebuild,
    [ValidatePattern('^v?[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?\z')]
    [string]$Version = '0.6.3'
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Version = $Version -replace '^v', ''

function Invoke-Checked {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    Write-Host (">> " + $Command + ' ' + ($Arguments -join ' ')) -ForegroundColor DarkGray
    Push-Location -LiteralPath $workspace
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $Command"
        }
    } finally {
        Pop-Location
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
    throw 'electron-builder is missing. Run npm ci first.'
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

# 目录模式打包：绿色便携以「zip 解压即用」的文件夹分发。
# 不用单文件 portable——它会自解压到 %TEMP%，数据写进临时目录、退出即丢，且部分机器上
# 会触发杀软拦截/深路径问题导致内置 JRE 起不来（后端 30 秒不健康、需管理员）。
Invoke-Checked $electronBuilder @('--win', 'dir', '--publish', 'never', "--config.extraMetadata.version=$Version")

$unpacked = Join-Path $workspace 'dist\win-unpacked'
if (-not (Test-Path -LiteralPath (Join-Path $unpacked 'Drill Notebook.exe'))) {
    throw "electron-builder completed but dist\win-unpacked\Drill Notebook.exe is missing."
}

$zip = Join-Path $workspace ("dist\Drill-Notebook-$Version-win-x64-portable.zip")
Compress-Archive -Path (Join-Path $unpacked '*') -DestinationPath $zip -CompressionLevel Optimal -Force

if (-not (Test-Path -LiteralPath $zip -PathType Leaf)) {
    throw "electron-builder completed but the portable zip was not found: $zip"
}

Write-Host 'Portable package created:' -ForegroundColor Green
Write-Host $zip
