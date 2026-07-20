$ErrorActionPreference = 'Continue'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = "$workspace\runtime-portable-smoke"
$jar = "$workspace\backend\target\drill-notebook-backend-0.1.0.jar"
$jdk17 = "$env:USERPROFILE\.jdk\jdk-17\jdk-17.0.19+10"
$javaCmd = "$jdk17\bin\java.exe"

$pass = 0
$fail = 0
$results = @()

function Test-Result($name, $ok, $detail = '') {
    if ($ok) { $script:pass++; $icon = 'PASS' }
    else { $script:fail++; $icon = 'FAIL' }
    $msg = "$icon : $name"
    if ($detail) { $msg += " ($detail)" }
    Write-Host $msg
    $script:results += $msg
}

# ── Start backend ──
Write-Host "`n=== Starting backend ==="
Remove-Item "$root\runtime\backend.port" -Force -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $javaCmd `
    -ArgumentList "-Dapp.root=$root", '-jar', $jar, '--server.address=127.0.0.1', '--server.port=0' `
    -PassThru -NoNewWindow `
    -RedirectStandardOutput "$root\runtime\backend.stdout.log" `
    -RedirectStandardError "$root\runtime\backend.stderr.log"

$port = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    $portFile = "$root\runtime\backend.port"
    if (Test-Path $portFile) {
        $port = (Get-Content $portFile -Raw).Trim()
        try { $h = Invoke-RestMethod "http://127.0.0.1:$port/api/health" -TimeoutSec 3; if ($h.status -eq 'UP') { break } } catch {}
    }
}
if (-not $port) { Write-Host "Backend failed to start!"; exit 1 }
$base = "http://127.0.0.1:$port"
Write-Host "Backend running on port $port"

try {
    # ═══════════════════════════════════════════════
    # 1. Markdown 导入：单选/多选/填空/判断/作文
    # ═══════════════════════════════════════════════
    Write-Host "`n── 1. Markdown 导入 ──"

    $bank = Invoke-RestMethod -Method Post -Uri "$base/api/banks" -ContentType 'application/json' `
        -Body '{"name":"MVP Test Bank","sourceType":"markdown"}'
    Test-Result '创建题库' ($bank.id -gt 0) "id=$($bank.id)"

    $sample = Get-Content "$workspace\resources\sample-bank.md" -Raw -Encoding utf8
    $importBody = @{ content = $sample } | ConvertTo-Json -Depth 10
    $import1 = Invoke-RestMethod -Method Post -Uri "$base/api/banks/$($bank.id)/import/markdown" `
        -ContentType 'application/json' -Body $importBody
    Test-Result 'Markdown导入' ($import1.imported -gt 0) "imported=$($import1.imported)"

    $import2 = Invoke-RestMethod -Method Post -Uri "$base/api/banks/$($bank.id)/import/markdown" `
        -ContentType 'application/json' -Body $importBody
    Test-Result '重复导入跳过' ($import2.skipped -gt 0) "skipped=$($import2.skipped)"

    $allQ = Invoke-RestMethod "$base/api/banks/$($bank.id)/questions"
    $types = ($allQ | ForEach-Object { $_.type } | Sort-Object -Unique) -join ','
    Test-Result '包含多种题型' ($allQ.Count -ge 4) "types=$types"

    # ═══════════════════════════════════════════════
    # 2. 刷题：高级排序 + 错题追踪
    # ═══════════════════════════════════════════════
    Write-Host "`n── 2. 刷题 & 错题 ──"

    $sessionBody = @{ bankId = $bank.id; shuffle = $false; limit = 3 } | ConvertTo-Json
    $session = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions" `
        -ContentType 'application/json' -Body $sessionBody
    Test-Result '创建刷题会话' ($session.questions.Count -gt 0) "count=$($session.questions.Count)"

    $q1 = $session.questions[0]
    $wrongBody = @{ questionId = $q1.id; userAnswer = '__WRONG__'; timeSpent = 1 } | ConvertTo-Json
    $wrong = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions/$($session.sessionId)/submit" `
        -ContentType 'application/json' -Body $wrongBody
    Test-Result '提交错误答案' ($wrong.isCorrect -eq $false)

    $wrongList = Invoke-RestMethod "$base/api/quiz/wrong"
    Test-Result '错题列表有记录' ($wrongList.Count -gt 0) "count=$($wrongList.Count)"

    $correctBody = @{ questionId = $q1.id; userAnswer = $q1.answer; timeSpent = 1 } | ConvertTo-Json
    $correct = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions/$($session.sessionId)/submit" `
        -ContentType 'application/json' -Body $correctBody
    Test-Result '提交正确答案' ($correct.isCorrect -eq $true)

    $clearedWrong = Invoke-RestMethod "$base/api/quiz/wrong"
    Test-Result '错题清除' ($clearedWrong.Count -eq 0)

    # ═══════════════════════════════════════════════
    # 3. 笔记本：快照 / KaTeX / Mermaid 保存
    # ═══════════════════════════════════════════════
    Write-Host "`n── 3. 笔记本 ──"

    $notebooks = Invoke-RestMethod "$base/api/notebooks"
    Test-Result '笔记本列表' ($notebooks.Count -gt 0)

    $pages = Invoke-RestMethod "$base/api/notebooks/$($notebooks[0].id)/pages"
    Test-Result '页面列表' ($pages.Count -gt 0)

    $page = Invoke-RestMethod -Method Post -Uri "$base/api/notes/pages/$($pages[0].id)/questions/$($q1.id)" `
        -ContentType 'application/json' -Body '{}'
    Test-Result '插入题目快照' ($page -ne $null)

    $reloaded = Invoke-RestMethod "$base/api/note-pages/$($pages[0].id)"
    Test-Result '笔记可加载' ($reloaded -ne $null)

    $noteContent = @{
        content = @(
            @{ type = 'paragraph'; content = @(@{ type = 'text'; text = 'KaTeX test: E=mc^2' }) },
            @{ type = 'paragraph'; content = @(@{ type = 'text'; text = 'Mermaid test:' }) }
        )
    }
    $noteBodyJson = @{ content = $noteContent } | ConvertTo-Json -Depth 10
    $saved = Invoke-RestMethod -Method Put -Uri "$base/api/note-pages/$($pages[0].id)" `
        -ContentType 'application/json' -Body $noteBodyJson
    Test-Result '保存笔记' ($saved -ne $null)

    # ═══════════════════════════════════════════════
    # 4. 背题 & 知识点
    # ═══════════════════════════════════════════════
    Write-Host "`n── 4. 背题 & 知识点 ──"

    $memBody = @{ bankId = $bank.id; mode = 'memorize'; shuffle = $true; limit = 5 } | ConvertTo-Json
    $memorize = Invoke-RestMethod -Method Post -Uri "$base/api/quiz/sessions" `
        -ContentType 'application/json' -Body $memBody
    Test-Result '创建背题会话' ($memorize.questions.Count -gt 0) "count=$($memorize.questions.Count)"

    $kpBody = @{ title = 'MassEnergy'; content = 'E=mc^2'; tags = @('physics') } | ConvertTo-Json
    $kp = Invoke-RestMethod -Method Post -Uri "$base/api/knowledge-points" `
        -ContentType 'application/json' -Body $kpBody
    Test-Result '创建知识点' ($kp.id -gt 0) "id=$($kp.id)"

    $kpList = Invoke-RestMethod "$base/api/knowledge-points"
    Test-Result '知识点列表' ($kpList.Count -gt 0)

    # ═══════════════════════════════════════════════
    # 5. AI 聊天（加密、OpenAI 兼容、多模态、会话）
    # ═══════════════════════════════════════════════
    Write-Host "`n── 5. AI 聊天 ──"

    $aiConfigBody = @{ provider = 'custom'; endpoint = 'mock://local'; model = 'local-demo'; apiKey = 'demo-local-key' } | ConvertTo-Json
    $aiConfig = Invoke-RestMethod -Method Put -Uri "$base/api/ai/config" `
        -ContentType 'application/json' -Body $aiConfigBody
    Test-Result '配置AI(mock)' ($null -ne $aiConfig)

    $redacted = Invoke-RestMethod "$base/api/ai/config"
    Test-Result 'API Key已加密' ($redacted.hasKey -eq $true)

    $chatBody = @{ messages = @(@{ role = 'user'; content = '你好' }) } | ConvertTo-Json -Depth 10
    $chat = Invoke-RestMethod -Method Post -Uri "$base/api/ai/chat" `
        -ContentType 'application/json' -Body $chatBody
    Test-Result 'AI对话' ($chat.reply -match '本地演示回复')

    $mmContent = @(
        @{ type = 'text'; text = 'describe' },
        @{ type = 'image_url'; image_url = @{ url = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=' } }
    )
    $mmMessage = @{ role = 'user'; content = $mmContent }
    $mmBody = @{ messages = @($mmMessage) } | ConvertTo-Json -Depth 10
    $mm = Invoke-RestMethod -Method Post -Uri "$base/api/ai/chat" -ContentType 'application/json' -Body $mmBody
    Test-Result 'AI多模态' ($mm.reply -match '图片附件')

    $sessions = Invoke-RestMethod "$base/api/ai/sessions"
    Test-Result 'AI会话列表' ($sessions.Count -gt 0)

    # ═══════════════════════════════════════════════
    # 6. 导出 (HTML)
    # ═══════════════════════════════════════════════
    Write-Host "`n── 6. 导出 ──"

    $exportBody = @{ title = 'Test'; markdown = '# Hello' } | ConvertTo-Json
    $exportHtml = Invoke-RestMethod -Method Post -Uri "$base/api/export/html" `
        -ContentType 'application/json' -Body $exportBody
    Test-Result '导出HTML' ($exportHtml -match '<html') "length=$($exportHtml.Length)"

    # ═══════════════════════════════════════════════
    # 7. 记忆曲线 (SM-2 间隔重复)
    # ═══════════════════════════════════════════════
    Write-Host "`n── 7. 记忆曲线 ──"

    # 7.1 复习方案管理
    $configs = Invoke-RestMethod "$base/api/review/configs"
    Test-Result '复习方案列表' ($configs.Count -ge 3) "count=$($configs.Count)"

    $defaultConfig = $configs | Where-Object { $_.isDefault } | Select-Object -First 1
    Test-Result '存在默认方案' ($null -ne $defaultConfig) "name=$($defaultConfig.name)"

    # 验证预设方案名称
    $names = ($configs | ForEach-Object { $_.name }) -join ','
    Test-Result '包含标准/考前/保守/验证' ($names -match '标准模式' -and $names -match '考前突击' -and $names -match '保守学习' -and $names -match '验证模式') "names=$names"

    # 新建测试方案
    $testIntervals = @{'1' = 0.001; '2' = 0.002; '3' = 0.004}
    $newConfigBody = @{
        name = '自动化测试方案'; isDefault = $false
        intervals = $testIntervals
        initialEf = 2.5; minimumEf = 1.3; maxIntervalDays = 7
        wrongStrategy = 'reset'; wrongFixedDays = 0.001
        dailyNewLimit = 50; dailyReviewLimit = 200
        priorityMode = 'due_first'
    } | ConvertTo-Json -Depth 10
    $createdConfig = Invoke-RestMethod -Method Post -Uri "$base/api/review/configs" `
        -ContentType 'application/json' -Body $newConfigBody
    Test-Result '新建复习方案' ($createdConfig.id -gt 0) "id=$($createdConfig.id)"

    $configId = $createdConfig.id
    # 更新方案
    $updatedIntervals = @{'1' = 0.002; '2' = 0.004}
    $updateConfigBody = @{
        name = '自动化测试方案-已更新'; intervals = $updatedIntervals
        initialEf = 2.6
    } | ConvertTo-Json -Depth 10
    $updatedConfig = Invoke-RestMethod -Method Put -Uri "$base/api/review/configs/$configId" `
        -ContentType 'application/json' -Body $updateConfigBody
    Test-Result '更新复习方案' ($updatedConfig.ok -eq $true)

    # 7.2 加入复习计划
    $questionIds = $allQ | Select-Object -First 5 -ExpandProperty id
    $enrollBody = @{ itemType = 'question'; itemIds = @($questionIds); configId = $configId } | ConvertTo-Json -Depth 10
    $enrolled = Invoke-RestMethod -Method Post -Uri "$base/api/review/enroll" `
        -ContentType 'application/json' -Body $enrollBody
    $enrolledCount = ($enrolled | Where-Object { $_.status -eq 'enrolled' }).Count
    Test-Result '批量加入计划' ($enrolledCount -eq 5) "enrolled=$enrolledCount"

    # 重复加入应跳过
    $reEnroll = Invoke-RestMethod -Method Post -Uri "$base/api/review/enroll" `
        -ContentType 'application/json' -Body $enrollBody
    $alreadyCount = ($reEnroll | Where-Object { $_.status -eq 'already_enrolled' }).Count
    Test-Result '重复加入跳过' ($alreadyCount -eq 5) "already=$alreadyCount"

    # 7.3 到期项目查询
    $due = Invoke-RestMethod "$base/api/review/due?type=question&configId=$configId"
    Test-Result '到期列表不为空' ($due.Count -gt 0) "dueCount=$($due.Count)"

    # 新加入的应为 new 状态
    $newStatus = $due | Where-Object { $_.status -eq 'new' }
    Test-Result '新加入项目状态为new' ($newStatus.Count -eq 5) "newCount=$($newStatus.Count)"

    # 到期项目应包含题目数据
    $withQuestion = $due | Where-Object { $null -ne $_.question }
    Test-Result '到期项目关联题目' ($withQuestion.Count -gt 0) "withQuestion=$($withQuestion.Count)"

    # 7.4 提交复习结果
    $firstItem = $due | Select-Object -First 1
    $submitBody = @{ scheduleId = $firstItem.id; quality = 4; responseTime = 5; source = 'test' } | ConvertTo-Json
    $submitted = Invoke-RestMethod -Method Post -Uri "$base/api/review/submit" `
        -ContentType 'application/json' -Body $submitBody
    Test-Result '提交复习(记住)' ($null -ne $submitted.logId) "logId=$($submitted.logId)"

    # 验证 SM-2 字段
    Test-Result 'SM-2 返回EF' ($submitted.ef -is [double] -or $submitted.ef -is [int]) "ef=$($submitted.ef)"
    Test-Result 'SM-2 返回间隔' ($submitted.interval -is [double] -or $submitted.interval -is [int]) "interval=$($submitted.interval)"
    Test-Result 'SM-2 返回状态' ($submitted.status -match 'learning|review') "status=$($submitted.status)"
    Test-Result 'SM-2 返回下次复习' ($null -ne $submitted.nextReview) "nextReview=$($submitted.nextReview)"

    # 再提交一个"忘记"结果
    $secondItem = $due | Select-Object -Skip 1 -First 1
    $forgotBody = @{ scheduleId = $secondItem.id; quality = 0; source = 'test' } | ConvertTo-Json
    $forgot = Invoke-RestMethod -Method Post -Uri "$base/api/review/submit" `
        -ContentType 'application/json' -Body $forgotBody
    Test-Result '提交复习(忘记)' ($null -ne $forgot.logId) "logId=$($forgot.logId)"

    # 质量0应该重置间隔
    Test-Result '忘记后间隔重置' ($forgot.interval -le 1) "interval=$($forgot.interval)"

    # 7.5 查询单项复习进展
    $detail = Invoke-RestMethod "$base/api/review/schedule/question/$($questionIds[0])"
    Test-Result '查询复习进展' ($detail.enrolled -eq $true) "status=$($detail.status)"

    # 应有日志记录
    Test-Result '复习日志存在' ($detail.recentLogs.Count -gt 0) "logCount=$($detail.recentLogs.Count)"

    # 7.6 复习统计
    $stats = Invoke-RestMethod "$base/api/review/stats?configId=$configId"
    Test-Result '总登记数' ($stats.totalEnrolled -gt 0) "enrolled=$($stats.totalEnrolled)"
    $statsLearning = $stats.learningCount + $stats.reviewCount
    Test-Result '学习中+复习中' ($statsLearning -gt 0) "learning+review=$statsLearning"
    Test-Result '新加入数' ($stats.newCount -ge 3) "newCount=$($stats.newCount)"

    # 7.7 重置复习进度
    $reset = Invoke-RestMethod -Method Post -Uri "$base/api/review/reset/question/$($questionIds[0])" `
        -ContentType 'application/json' -Body '{}'
    Test-Result '重置复习进度' ($reset.ok -eq $true)

    $resetDetail = Invoke-RestMethod "$base/api/review/schedule/question/$($questionIds[0])"
    Test-Result '重置后状态为new' ($resetDetail.status -eq 'new') "status=$($resetDetail.status)"
    Test-Result '重置后counter归零' ($resetDetail.totalReviews -eq 0 -and $resetDetail.totalWrong -eq 0) "reviews=$($resetDetail.totalReviews)"

    # 7.8 删除方案
    # 删除我们创建的非默认方案
    $createdConfigId = $createdConfig.id
    $del = Invoke-RestMethod -Method Delete -Uri "$base/api/review/configs/$createdConfigId"
    Test-Result '删除非默认方案' ($del.ok -eq $true)

    # 尝试删除默认方案应被拒绝
    $defaultId = $defaultConfig.id
    $deleteDefaultFailed = $false
    try {
        Invoke-RestMethod -Method Delete -Uri "$base/api/review/configs/$defaultId" | Out-Null
    } catch { $deleteDefaultFailed = $true }
    Test-Result '默认方案不可删除' $deleteDefaultFailed 'protected'

} finally {
    if ($proc -and -not $proc.HasExited) { taskkill /PID $proc.Id /T /F | Out-Null }
}

# ═══════════════════════════════════════════════
Write-Host "`n========================================"
Write-Host "  RESULTS: $pass passed, $fail failed"
Write-Host "========================================"
foreach ($r in $results) { Write-Host "  $r" }
if ($fail -gt 0) { exit 1 } else { exit 0 }
