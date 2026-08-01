<#
.SYNOPSIS
    Downloads the pinned bge-small-zh-v1.5 model fixture from HuggingFace.
.DESCRIPTION
    Downloads all 7 files of Qdrant/bge-small-zh-v1.5@46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59
    into WORKSPACE_ROOT/runtime-portable/data/models/embedding-fixtures/ and verifies
    exact sizes and SHA-256.  Only the first 5 files are runtime-required; all 7 are audited.
    The target directory is ALWAYS constrained to WORKSPACE_ROOT.
.PARAMETER ModelDir
    Target directory relative to workspace root.  Defaults to
    <workspace>\runtime-portable\data\models\embedding-fixtures.
.PARAMETER PassThru
    Print the final fixture directory path to stdout (for chaining).
.EXAMPLE
    .\scripts\fetch-embedding-fixture.ps1
    .\scripts\fetch-embedding-fixture.ps1 -PassThru
#>

param(
    [string]$ModelDir = "",
    [switch]$PassThru
)

$ErrorActionPreference = "Stop"

# ---- resolve workspace root (script parent) ----
$scriptPath = Resolve-Path $PSScriptRoot
$workspaceRoot = (Resolve-Path (Split-Path -Parent $scriptPath)).Path.TrimEnd('\')

# ---- resolve target directory with containment ----
$defaultSubPath = "runtime-portable\data\models\embedding-fixtures"

if (-not $ModelDir) {
    $ModelDir = Join-Path $workspaceRoot $defaultSubPath
}

# Canonical full-path containment check
# Resolve to an absolute path (does not require the dir to exist)
$resolvedTarget = [System.IO.Path]::GetFullPath($ModelDir).TrimEnd('\')
$containmentPrefix = "$workspaceRoot\"
if ($resolvedTarget -notlike "$containmentPrefix*" -and $resolvedTarget -ne $workspaceRoot) {
    Write-Host "[fetch-embedding-fixture] ERROR: ModelDir must be under workspace root."
    Write-Host "  Workspace root : $workspaceRoot"
    Write-Host "  ModelDir       : $ModelDir"
    Write-Host "  Resolved       : $resolvedTarget"
    exit 1
}

Write-Host "[fetch-embedding-fixture] Workspace root : $workspaceRoot"
Write-Host "[fetch-embedding-fixture] Target         : $resolvedTarget"

$ModelDir = $resolvedTarget

# ---- canonical inventory ----
$BaseUrl = "https://huggingface.co/Qdrant/bge-small-zh-v1.5/resolve/46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59"

$Manifest = @(
    @{ File = "model_optimized.onnx";       Size = 94781076; Hash = "1294ea4b6331115a353d81f96b85e8c8d7fdcc284453d5b2fab5b016230aad38" },
    @{ File = "tokenizer.json";             Size = 439125;   Hash = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26" },
    @{ File = "config.json";                Size = 739;      Hash = "9088751d39abbf86ec3d19ffca92ad62ad19075f7e59712e6c71217fa125d1d3" },
    @{ File = "tokenizer_config.json";      Size = 367;      Hash = "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a" },
    @{ File = "special_tokens_map.json";    Size = 125;      Hash = "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3" },
    @{ File = "vocab.txt";                  Size = 109540;   Hash = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c" },
    @{ File = "ort_config.json";            Size = 1234;     Hash = "97e78d1d21c2eb719e865b018f17915df6a12ed987446eb7f3f3a783a5afb1e1" }
)

$TotalExpectedSize = 95332206  # 95,332,206 bytes

# ---- create target directory ----
New-Item -ItemType Directory -Path $ModelDir -Force | Out-Null

# ---- download each file ----
$allOk = $true
foreach ($entry in $Manifest) {
    $file = $entry.File
    $expectedSize = $entry.Size
    $expectedHash = $entry.Hash
    $targetPath = Join-Path $ModelDir $file

    Write-Host "[fetch-embedding-fixture] Downloading $file ($expectedSize bytes)..."

    # Download with redirect support
    $url = "$BaseUrl/$file"
    try {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add("User-Agent", "drill-notebook/0.1.0")
        $data = $wc.DownloadData($url)
        $wc.Dispose()

        # Check size
        if ($data.Length -ne $expectedSize) {
            Write-Host "[fetch-embedding-fixture] FAIL: $file size mismatch: got $($data.Length), expected $expectedSize"
            $allOk = $false
            continue
        }

        # Check SHA-256
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        $hashBytes = $sha256.ComputeHash($data)
        $hashStr = [System.BitConverter]::ToString($hashBytes).Replace("-", "").ToLower()
        if ($hashStr -ne $expectedHash) {
            Write-Host "[fetch-embedding-fixture] FAIL: $file hash mismatch: got $hashStr, expected $expectedHash"
            $allOk = $false
            continue
        }

        # Write to target
        [System.IO.File]::WriteAllBytes($targetPath, $data)
        Write-Host "[fetch-embedding-fixture] OK   $file ($expectedSize bytes, SHA-256 verified)"
    }
    catch {
        Write-Host "[fetch-embedding-fixture] FAIL: $file download error: $_"
        $allOk = $false
    }
}

# ---- summary ----
if ($allOk) {
    Write-Host "[fetch-embedding-fixture] ALL 7 FILES VERIFIED. Total: $TotalExpectedSize bytes."
    Write-Host "[fetch-embedding-fixture] Fixture directory: $ModelDir"
    if ($PassThru) { Write-Output $ModelDir }
    exit 0
}
else {
    Write-Host "[fetch-embedding-fixture] ONE OR MORE FILES FAILED verification."
    exit 1
}
