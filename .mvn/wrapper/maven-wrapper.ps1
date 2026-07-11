param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments
)

$ErrorActionPreference = 'Stop'
$wrapperRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$cacheRoot = Join-Path $wrapperRoot 'dists'
$version = '3.9.9'
$distributionRoot = Join-Path $cacheRoot "apache-maven-$version"
$mavenHome = Join-Path $distributionRoot "apache-maven-$version"
$zipPath = Join-Path $cacheRoot "apache-maven-$version-bin.zip"
$url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'

if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
    if (-not (Test-Path $zipPath)) { Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $distributionRoot -Force
}

& (Join-Path $mavenHome 'bin\mvn.cmd') @MavenArguments
exit $LASTEXITCODE
