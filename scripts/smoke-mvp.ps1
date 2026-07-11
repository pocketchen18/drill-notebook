param(
    [string]$AppRoot = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'runtime-portable-smoke')
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = [System.IO.Path]::GetFullPath($AppRoot)
$jar = Join-Path $workspace 'backend\target\drill-notebook-backend-0.1.0.jar'
$sample = Join-Path $workspace 'resources\sample-bank.md'
$evidence = Join-Path $workspace '.omo\evidence\task-13-smoke.txt'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'data'), (Join-Path $root 'runtime'), (Split-Path $evidence) | Out-Null
if (-not $root.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Smoke AppRoot must stay inside the workspace.' }
foreach ($databaseFile in @('study.db', 'study.db-shm', 'study.db-wal')) {
    Remove-Item -LiteralPath (Join-Path $root "data\$databaseFile") -Force -ErrorAction SilentlyContinue
}
Remove-Item -LiteralPath (Join-Path $root 'runtime\backend.port') -Force -ErrorAction SilentlyContinue

if (-not (Test-Path $jar)) { throw "Backend jar not found: $jar. Run mvn -f backend/pom.xml package first." }
$javaHome = Join-Path $root 'runtime\java-home'
$tmp = Join-Path $root 'runtime\tmp'
New-Item -ItemType Directory -Force -Path $javaHome, $tmp | Out-Null
$stdout = Join-Path $root 'runtime\backend.stdout.log'
$stderr = Join-Path $root 'runtime\backend.stderr.log'
$javaArguments = [System.Collections.Generic.List[string]]::new()
[void]$javaArguments.Add('-Dapp.root=' + $root)
[void]$javaArguments.Add('-Duser.home=' + $javaHome)
[void]$javaArguments.Add('-Djava.io.tmpdir=' + $tmp)
[void]$javaArguments.Add('-Dfile.encoding=UTF-8')
[void]$javaArguments.Add('-jar')
[void]$javaArguments.Add($jar)
[void]$javaArguments.Add('--server.address=127.0.0.1')
[void]$javaArguments.Add('--server.port=0')
$process = $null
$port = $null
$stdoutTask = $null
$stderrTask = $null
$resultLines = [System.Collections.Generic.List[string]]::new()
try {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'java.exe'
    $startInfo.WorkingDirectory = $root
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $quotedArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in $javaArguments) { [void]$quotedArguments.Add('"' + $argument + '"') }
    $startInfo.Arguments = $quotedArguments.ToArray() -join ' '
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        $portFile = Join-Path $root 'runtime\backend.port'
        if (Test-Path $portFile) {
            $port = [int](Get-Content -LiteralPath $portFile -Raw).Trim()
            try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2; if ($health.status -eq 'UP') { break } } catch { }
        }
    }
    if (-not $port) { throw 'Backend health check timed out.' }
    $base = "http://127.0.0.1:$port"
    $bank = Invoke-RestMethod -Method Post -Uri "$base/api/banks" -ContentType 'application/json' -Body (@{ name = 'Smoke Bank'; sourceType = 'markdown' } | ConvertTo-Json)
    $content = Get-Content -LiteralPath $sample -Raw -Encoding utf8
    $first = Invoke-RestMethod -Method Post -Uri "$base/api/banks/$($bank.id)/import/markdown" -ContentType 'application/json' -Body (@{ content = $content } | ConvertTo-Json -Depth 10)
    $second = Invoke-RestMethod -Method Post -Uri "$base/api/banks/$($bank.id)/import/markdown" -ContentType 'application/json' -Body (@{ content = $content } | ConvertTo-Json -Depth 10)
    $session = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions" -ContentType 'application/json' -Body (@{ bankId = $bank.id; shuffle = $false; limit = 2 } | ConvertTo-Json)
    $question = $session.questions[0]
    $wrong = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions/$($session.sessionId)/submit" -ContentType 'application/json' -Body (@{ questionId = $question.id; userAnswer = 'A'; timeSpent = 1 } | ConvertTo-Json)
    $wrongList = Invoke-RestMethod -Uri "$base/api/quiz/wrong"
    $correct = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions/$($session.sessionId)/submit" -ContentType 'application/json' -Body (@{ questionId = $question.id; userAnswer = 'B'; timeSpent = 1 } | ConvertTo-Json)
    $clearedWrongList = Invoke-RestMethod -Uri "$base/api/quiz/wrong"
    if ($wrong.isCorrect -ne $false -or $wrongList.Count -lt 1 -or $correct.isCorrect -ne $true -or $clearedWrongList.Count -ne 0) { throw 'Wrong-answer lifecycle assertion failed.' }

    $notebooks = Invoke-RestMethod -Uri "$base/api/notebooks"
    $pages = Invoke-RestMethod -Uri "$base/api/notebooks/$($notebooks[0].id)/pages"
    $page = Invoke-RestMethod -Method Post -Uri "$base/api/notes/pages/$($pages[0].id)/questions/$($question.id)" -ContentType 'application/json' -Body '{}'
    Invoke-RestMethod -Method Delete -Uri "$base/api/questions/$($question.id)" | Out-Null
    $reloadedPage = Invoke-RestMethod -Uri "$base/api/note-pages/$($pages[0].id)"
    $snapshotBlocks = @($reloadedPage.content.content | Where-Object { $_.type -eq 'questionBlock' })
    if ($snapshotBlocks.Count -ne 1 -or $snapshotBlocks[0].attrs.snapshot.stem -ne $question.stem) { throw 'QuestionBlock snapshot assertion failed.' }

    $aiConfig = Invoke-RestMethod -Method Put -Uri "$base/api/ai/config" -ContentType 'application/json' -Body (@{ provider = 'custom'; endpoint = 'mock://local'; model = 'local-demo'; apiKey = 'demo-local-key' } | ConvertTo-Json)
    $redacted = Invoke-RestMethod -Uri "$base/api/ai/config"
    $chat = Invoke-RestMethod -Method Post -Uri "$base/api/ai/chat" -ContentType 'application/json' -Body (@{ messages = @(@{ role = 'user'; content = '你好' }) } | ConvertTo-Json -Depth 10)
    $multimodal = Invoke-RestMethod -Method Post -Uri "$base/api/ai/chat" -ContentType 'application/json' -Body (@{ messages = @(@{ role = 'user'; content = @(@{ type = 'text'; text = '请描述图片' }, @{ type = 'image_url'; image_url = @{ url = 'data:image/png;base64,AA==' } }) }) } | ConvertTo-Json -Depth 10)
    if (-not $redacted.hasKey -or $chat.reply -notmatch '本地演示回复' -or $multimodal.reply -notmatch '图片附件') { throw 'AI configuration/chat assertion failed.' }
    $resultLines.Add("Health: $($health.status)")
    $resultLines.Add("Imported: $($first.imported), skipped on re-import: $($second.skipped)")
    $resultLines.Add("Session question count: $($session.questions.Count)")
    $resultLines.Add("Wrong then correct: $($wrong.isCorrect -eq $false) -> $($correct.isCorrect -eq $true), cleared count: $($clearedWrongList.Count)")
    $resultLines.Add("QuestionBlock snapshot after delete: $($snapshotBlocks.Count)")
    $resultLines.Add("AI redacted config: hasKey=$($redacted.hasKey), mock chat returned=$($chat.reply -match '本地演示回复')")
    $resultLines.Add("AI multimodal mock: returned=$($multimodal.reply -match '图片附件')")
    $resultLines.Add("Database: $($health.dbPath)")
    $resultLines | Set-Content -LiteralPath $evidence -Encoding utf8
    Write-Output ($resultLines -join [Environment]::NewLine)
} catch {
    "Smoke failed: $($_.Exception.Message)" | Set-Content -LiteralPath $evidence -Encoding utf8
    throw
} finally {
    if ($process -and -not $process.HasExited) {
        taskkill.exe /PID $process.Id /T /F | Out-Null
    }
    if ($stdoutTask) { $stdoutTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stdout -Encoding utf8 }
    if ($stderrTask) { $stderrTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stderr -Encoding utf8 }
}
