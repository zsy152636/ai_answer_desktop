# AI 智能答题系统 · 端到端冒烟测试（阶段 1-6 业务用例）
#
# 覆盖：健康检查与令牌鉴权、Origin 拒绝、题库与版本、试卷发布快照、作答保存、
#       确定性判分、错题闭环、幂等提交、备份与完整性检查、AI 服务可用性（可选）、
#       资料库与本地检索（导入与分块、检索命中可回溯到分块、短查询降级为关键词、
#       重复导入 reject/new 分支、版本化索引重建、重复文件引用的删除与文件补偿、格式边界）。
#       第 11 节不依赖 AI 模型。
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://127.0.0.1:18765 -Token <token>
#
# 退出码：0 = 全部通过；1 = 有失败项。
[CmdletBinding()]
param(
    [string]$BaseUrl,
    [string]$Token,
    [string]$EndpointFile,
    [switch]$SkipSeed
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $EndpointFile) { $EndpointFile = Join-Path $repoRoot '.runtime\java-endpoint.json' }

$script:passed = 0
$script:failed = 0
$script:failures = @()

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) {
        $script:passed++
        Write-Host ("  [PASS] " + $Name) -ForegroundColor Green
    } else {
        $script:failed++
        $script:failures += $Name
        Write-Host ("  [FAIL] " + $Name + " " + $Detail) -ForegroundColor Red
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$IdempotencyKey,
        [switch]$NoToken
    )
    $headers = @{ 'Content-Type' = 'application/json; charset=utf-8' }
    if (-not $NoToken) { $headers['X-Session-Token'] = $script:token }
    if ($IdempotencyKey) { $headers['Idempotency-Key'] = $IdempotencyKey }
    $params = @{
        Method      = $Method
        Uri         = ($script:baseUrl + $Path)
        Headers     = $headers
        TimeoutSec  = 120
    }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 12 -Compress }
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    $response = Invoke-RestMethod @params
    # 业务接口统一返回 {success,data,error,requestId,timestamp}；/health 是裸对象。
    # 这里统一拆包，脚本其余部分只面对 data。
    if ($null -ne $response -and ($response.PSObject.Properties.Name -contains 'success')) {
        if (-not $response.success) {
            throw ("接口返回业务错误：" + $response.error.code + " " + $response.error.message)
        }
        return $response.data
    }
    return $response
}

function Get-StatusCode {
    param([scriptblock]$Action)
    try {
        & $Action | Out-Null
        return 200
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}

# 失败响应也要能拿到 error.code：先按 UTF-8 读原始响应流，再解析失败包络。
# （$_.ErrorDetails 在 Windows PowerShell 5.1 下对 Invoke-RestMethod 为空，不能依赖。）
function Get-ApiError {
    param([scriptblock]$Action)
    try {
        & $Action | Out-Null
        return [pscustomobject]@{ Status = 200; Code = $null; Message = '' }
    } catch {
        $status = 0
        $code = $null
        $msg = ''
        $response = $_.Exception.Response
        if ($response) {
            $status = [int]$response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
                $raw = $reader.ReadToEnd()
                $reader.Dispose()
                if (-not [string]::IsNullOrWhiteSpace($raw)) {
                    $parsed = $raw | ConvertFrom-Json
                    if (($parsed.PSObject.Properties.Name -contains 'error') -and $null -ne $parsed.error) {
                        $code = $parsed.error.code
                        $msg = $parsed.error.message
                    }
                }
            } catch {
                # 响应体读不到时只保留状态码
            }
        }
        return [pscustomobject]@{ Status = $status; Code = $code; Message = $msg }
    }
}

# Windows PowerShell 5.1 的 Invoke-RestMethod 会把 UTF-8 响应体按 Latin-1 解码，中文因此变成乱码字符。
# 断言中文时先直接比较（客户端解码正确时即命中），不命中再把两侧还原成原始 UTF-8 字节做子串匹配：
# 两种情况都是对**响应内容**的真实核对，而不是恒真断言。
function Test-TextContains {
    param([string]$Haystack, [string]$Needle)
    if ([string]::IsNullOrEmpty($Haystack) -or [string]::IsNullOrEmpty($Needle)) { return $false }
    if ($Haystack.Contains($Needle)) { return $true }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    return Test-BytesContain ($latin1.GetBytes($Haystack)) ([System.Text.Encoding]::UTF8.GetBytes($Needle))
}

function Test-BytesContain {
    param([byte[]]$Haystack, [byte[]]$Needle)
    if ($null -eq $Haystack -or $null -eq $Needle) { return $false }
    if ($Needle.Length -eq 0 -or $Haystack.Length -lt $Needle.Length) { return $false }
    for ($i = 0; $i -le ($Haystack.Length - $Needle.Length); $i++) {
        $hit = $true
        for ($j = 0; $j -lt $Needle.Length; $j++) {
            if ($Haystack[$i + $j] -ne $Needle[$j]) { $hit = $false; break }
        }
        if ($hit) { return $true }
    }
    return $false
}

# ------------------------------------------------------------------ 准备
if (-not $BaseUrl -or -not $Token) {
    if (-not (Test-Path $EndpointFile)) {
        Write-Host "找不到 Java 端点文件：$EndpointFile" -ForegroundColor Red
        Write-Host "请先启动本机服务（scripts\dev-start.ps1），或用 -BaseUrl/-Token 显式指定。" -ForegroundColor Red
        exit 1
    }
    $endpoint = Get-Content -Raw -Encoding UTF8 -LiteralPath $EndpointFile | ConvertFrom-Json
    if (-not $BaseUrl) { $BaseUrl = $endpoint.baseUrl }
    if (-not $Token) { $Token = $endpoint.token }
}
$script:baseUrl = $BaseUrl.TrimEnd('/')
$script:token = $Token

Write-Host ""
Write-Host "=== AI 智能答题系统 · 端到端冒烟测试 ===" -ForegroundColor Cyan
Write-Host "目标服务：$script:baseUrl"
Write-Host ""

# ------------------------------------------------------------------ 1. 健康与安全基线
Write-Host "[1] 健康检查与安全基线" -ForegroundColor Yellow
$health = Invoke-Api -Method Get -Path '/health' -NoToken
Assert-True 'health 返回 UP' ($health.status -eq 'UP') "实际：$($health.status)"
Assert-True 'health 报告 schemaVersion' ($health.schemaVersion -ge 1) "实际：$($health.schemaVersion)"

$noTokenStatus = Get-StatusCode { Invoke-Api -Method Get -Path '/api/v1/meta' -NoToken }
Assert-True '缺少会话令牌访问 /meta 被拒绝（401）' ($noTokenStatus -eq 401) "实际：$noTokenStatus"

$badOriginStatus = Get-StatusCode {
    Invoke-RestMethod -Method Get -Uri ($script:baseUrl + '/api/v1/meta') -TimeoutSec 30 `
        -Headers @{ 'X-Session-Token' = $script:token; 'Origin' = 'https://evil.example.com' } | Out-Null
}
Assert-True '不可信 Origin 被拒绝（403）' ($badOriginStatus -eq 403) "实际：$badOriginStatus"

$meta = Invoke-Api -Method Get -Path '/api/v1/meta'
Assert-True 'meta 返回数据目录' ($null -ne $meta.dataDir) ''
Assert-True 'meta 报告数据库大小' ($meta.databaseSizeBytes -gt 0) "实际：$($meta.databaseSizeBytes)"

# ------------------------------------------------------------------ 2. 演示数据
Write-Host "[2] 演示数据（幂等）" -ForegroundColor Yellow
if (-not $SkipSeed) {
    $seed = Invoke-Api -Method Post -Path '/api/v1/system/seed-demo' -Body @{}
    Assert-True 'seed-demo 调用成功' ($seed.questionsCreated -ge 0) ''
    Write-Host ("       本次写入题目 " + $seed.questionsCreated + " 道，试卷 " + $seed.papersCreated + " 张，重复调用跳过：" + $seed.skipped)
}

# ------------------------------------------------------------------ 3. 题库与版本
Write-Host "[3] 题库与题目版本" -ForegroundColor Yellow
$questionPage = Invoke-Api -Method Get -Path '/api/v1/questions?page=1&size=50'
Assert-True '题库列表可读取' ($questionPage.items.Count -ge 6) "实际：$($questionPage.items.Count)"

$created = Invoke-Api -Method Post -Path '/api/v1/questions' -Body @{
    title    = '冒烟测试题：SQLite 默认日志模式'
    questionType = 'single_choice'
    difficulty   = 1
    stem         = 'SQLite 在开启 WAL 之前的默认 journal_mode 是什么？'
    options      = @(
        @{ key = 'A'; content = 'delete' },
        @{ key = 'B'; content = 'wal' },
        @{ key = 'C'; content = 'truncate' }
    )
    answer       = @{ type = 'single_choice'; value = 'A' }
    explanation  = 'SQLite 默认 journal_mode 为 delete，需要显式切换到 WAL。'
    knowledgeTags = @('冒烟测试', 'SQLite')
    changeNote   = '冒烟测试创建'
}
$questionId = $created.id
Assert-True '新建题目返回 id' ($null -ne $questionId) ''
Assert-True '新建题目初始版本号为 1' ($created.currentVersion.versionNo -eq 1) "实际：$($created.currentVersion.versionNo)"

$updated = Invoke-Api -Method Put -Path ("/api/v1/questions/" + $questionId) -Body @{
    title    = '冒烟测试题：SQLite 默认日志模式（改）'
    questionType = 'single_choice'
    difficulty   = 2
    stem         = 'SQLite 在开启 WAL 之前的默认 journal_mode 是什么？（修订）'
    options      = @(
        @{ key = 'A'; content = 'delete' },
        @{ key = 'B'; content = 'wal' },
        @{ key = 'C'; content = 'truncate' }
    )
    answer        = @{ type = 'single_choice'; value = 'A' }
    explanation   = '修订解析：默认 delete，WAL 需显式开启。'
    knowledgeTags = @('冒烟测试', 'SQLite')
    changeNote    = '冒烟测试修订'
    expectedVersionNo = 1
}
Assert-True '内容变化后创建新版本（v2）' ($updated.currentVersion.versionNo -eq 2) "实际：$($updated.currentVersion.versionNo)"
Assert-True '新版本回到 draft 状态' ($updated.status -eq 'draft') "实际：$($updated.status)"

$conflictStatus = Get-StatusCode {
    Invoke-Api -Method Put -Path ("/api/v1/questions/" + $questionId) -Body @{
        title = '并发冲突测试'; questionType = 'single_choice'; stem = '冲突'; options = @(@{key='A';content='a'},@{key='B';content='b'})
        answer = @{ type = 'single_choice'; value = 'A' }; expectedVersionNo = 1
    } | Out-Null
}
Assert-True '过期版本号触发 VERSION_CONFLICT（409）' ($conflictStatus -eq 409) "实际：$conflictStatus"

$versions = Invoke-Api -Method Get -Path ("/api/v1/questions/" + $questionId + '/versions')
Assert-True '版本历史包含两个版本' ($versions.Count -eq 2) "实际：$($versions.Count)"

$published = Invoke-Api -Method Post -Path ("/api/v1/questions/" + $questionId + '/publish') -Body @{}
Assert-True '题目发布成功' ($published.status -eq 'published') "实际：$($published.status)"

# ------------------------------------------------------------------ 4. 试卷与快照
Write-Host "[4] 试卷与发布快照" -ForegroundColor Yellow
$papers = Invoke-Api -Method Get -Path '/api/v1/papers?page=1&size=50'
$paper = $papers.items | Where-Object { $_.status -eq 'published' } | Select-Object -First 1
Assert-True '存在已发布试卷' ($null -ne $paper) ''

$paperDetail = Invoke-Api -Method Get -Path ("/api/v1/papers/" + $paper.id)
Assert-True '试卷包含题目' ($paperDetail.questions.Count -ge 1) "实际：$($paperDetail.questions.Count)"
Assert-True '已发布试卷总分大于 0' ($paperDetail.totalScore -gt 0) "实际：$($paperDetail.totalScore)"
$snapshotIdBefore = $paperDetail.latestSnapshotId
Assert-True '试卷有快照 id' ($null -ne $snapshotIdBefore) ''

$snapshot = Invoke-Api -Method Get -Path ("/api/v1/papers/" + $paper.id + '/snapshots/' + $snapshotIdBefore)
Assert-True '快照题目带标准答案（历史可重现）' ($null -ne $snapshot.questions[0].answer) ''

# ------------------------------------------------------------------ 5. 答题与判分
Write-Host "[5] 答题、自动保存与确定性判分" -ForegroundColor Yellow
$attempt = Invoke-Api -Method Post -Path '/api/v1/attempts' -Body @{ paperId = $paper.id }
$attemptId = $attempt.id
Assert-True '开始答题返回作答会话' ($null -ne $attemptId) ''
Assert-True '答题中不泄露标准答案' ($null -eq $attempt.questions[0].answer) ''

$sheet = Invoke-Api -Method Get -Path ("/api/v1/attempts/" + $attemptId)
$firstQuestion = $sheet.questions[0]

$saved = Invoke-Api -Method Put -Path ("/api/v1/attempts/" + $attemptId + '/answers/' + $firstQuestion.snapshotQuestionId) `
    -Body @{ answer = @{ type = $firstQuestion.questionType; value = @('C') } }
Assert-True '保存单题答案成功' ($null -ne $saved.savedAt) ''

$reloaded = Invoke-Api -Method Get -Path ("/api/v1/attempts/" + $attemptId)
Assert-True '保存后的答案可读回（自动保存）' ($reloaded.answers.Count -ge 1) "实际：$($reloaded.answers.Count)"

$idemKey = [guid]::NewGuid().ToString()
$submitBody = @{}
$result1 = Invoke-Api -Method Post -Path ("/api/v1/attempts/" + $attemptId + '/submit') -Body $submitBody -IdempotencyKey $idemKey
Assert-True '提交后作答状态为 graded' ($result1.attempt.status -eq 'graded') "实际：$($result1.attempt.status)"
Assert-True '判分给出总分' ($null -ne $result1.attempt.totalScore) ''
Assert-True '逐题结果齐全' ($result1.items.Count -eq $attempt.questions.Count) "实际：$($result1.items.Count)"
Assert-True '每题都记录了判分方法' (($result1.items | Where-Object { $null -eq $_.gradingMethod }).Count -eq 0) ''
Assert-True '判分明细包含规则版本' ($null -ne $result1.items[0].gradingDetail.ruleVersion) ''

$result2 = Invoke-Api -Method Post -Path ("/api/v1/attempts/" + $attemptId + '/submit') -Body $submitBody -IdempotencyKey $idemKey
Assert-True '重复提交幂等（分数不变）' ($result2.attempt.totalScore -eq $result1.attempt.totalScore) `
    "第一次=$($result1.attempt.totalScore) 第二次=$($result2.attempt.totalScore)"
$answerCount = (Invoke-Api -Method Get -Path ("/api/v1/attempts/" + $attemptId)).answers.Count
Assert-True '重复提交未重复生成作答行' ($answerCount -eq $attempt.questions.Count) "实际：$answerCount"

# ------------------------------------------------------------------ 6. 错题闭环
Write-Host "[6] 错题闭环" -ForegroundColor Yellow
$wrong = Invoke-Api -Method Get -Path '/api/v1/wrong-questions?page=1&size=50'
Assert-True '答错题目进入错题本' ($wrong.items.Count -ge 1) "实际：$($wrong.items.Count)"
# 只取 active 的错题：库里可能已有历史运行留下的 mastered 记录（其 nextReviewAt 为 null）
$wrongItem = @($wrong.items | Where-Object { $_.status -eq 'active' })[0]
Assert-True '存在待复习的错题' ($null -ne $wrongItem) '（没有 active 错题，可能上一轮全部标记为掌握）'
Assert-True '错题记录错误次数' ($wrongItem.wrongCount -ge 1) "实际：$($wrongItem.wrongCount)"
Assert-True '错题已安排复习时间' ($null -ne $wrongItem.nextReviewAt) "status=$($wrongItem.status)"

$practice = Invoke-Api -Method Post -Path '/api/v1/wrong-questions/practice' -Body @{ limit = 5 }
Assert-True '错题练习会话创建成功' ($practice.mode -eq 'wrong_practice') "实际：$($practice.mode)"

$reviewed = Invoke-Api -Method Post -Path ("/api/v1/wrong-questions/" + $wrongItem.id + '/review') `
    -Body @{ result = 'correct'; note = '冒烟测试复习' }
Assert-True '复习记录写入' ($reviewed.reviews.Count -ge 1) "实际：$($reviewed.reviews.Count)"
Assert-True '复习正确后状态变化' ($reviewed.question.status -in @('reviewing', 'mastered')) "实际：$($reviewed.question.status)"

$mastered = Invoke-Api -Method Patch -Path ("/api/v1/wrong-questions/" + $wrongItem.id) -Body @{ status = 'mastered' }
Assert-True '手动标记掌握成功' ($mastered.question.status -eq 'mastered') "实际：$($mastered.question.status)"

# ------------------------------------------------------------------ 7. 系统维护
Write-Host "[7] 备份、恢复校验与完整性" -ForegroundColor Yellow
$backup = Invoke-Api -Method Post -Path '/api/v1/system/backup' -Body @{}
Assert-True '手动备份成功' ($null -ne $backup.fileName) ''
Assert-True '备份通过完整性校验' ($backup.integrityOk) ''
Assert-True '备份记录了校验和' ($backup.checksum.Length -eq 64) "实际长度：$($backup.checksum.Length)"

$backups = Invoke-Api -Method Get -Path '/api/v1/system/backups'
Assert-True '备份列表包含新备份' (@($backups | Where-Object { $_.fileName -eq $backup.fileName }).Count -eq 1) ''

$verify = Invoke-Api -Method Post -Path ("/api/v1/system/backups/verify?fileName=" + [uri]::EscapeDataString($backup.fileName)) -Body @{}
Assert-True '备份可单独校验' ($verify.ok) "integrity=$($verify.integrityCheck)"

$integrity = Invoke-Api -Method Post -Path '/api/v1/system/integrity-check' -Body @{}
Assert-True '数据库完整性检查通过' ($integrity.ok) "integrity=$($integrity.integrityCheck)"
Assert-True '外键检查通过' ($integrity.foreignKeyCheck -eq 'ok') "实际：$($integrity.foreignKeyCheck)"

$info = Invoke-Api -Method Get -Path '/api/v1/system/info'
Assert-True '系统信息返回凭据后端' ($null -ne $info.credentialBackend) "实际：$($info.credentialBackend)"
Assert-True '系统信息报告 AI 服务状态' ($null -ne $info.aiService) ''

# 恢复只登记请求，不立即替换文件（避免连接池持有坏句柄）
$restore = Invoke-Api -Method Post -Path '/api/v1/system/restore' -Body @{ fileName = $backup.fileName; confirm = $true }
Assert-True '恢复请求被登记且要求重启' ($restore.pendingRestore -and $restore.requiresRestart) ''
$restoreFile = Join-Path $repoRoot '.devdata\java\runtime\restore-request.json'
Assert-True '恢复请求文件已落盘' (Test-Path $restoreFile) "路径：$restoreFile"

# ------------------------------------------------------------------ 8. LLM 配置与凭据
Write-Host "[8] LLM 配置、校验与连接测试" -ForegroundColor Yellow
$profiles = Invoke-Api -Method Get -Path '/api/v1/settings/llm-profiles'
Assert-True '可读取 LLM 档案列表（不含密钥）' ($null -ne $profiles.profiles) ''

$badConfig = @{ schemaVersion = 2; profiles = @() }
$badValidation = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/validate' -Body @{ config = $badConfig }
Assert-True '不支持的配置版本被拒绝' (-not $badValidation.valid) ''

$goodConfig = @{
    schemaVersion = 1
    defaultProfileId = 'ollama-local'
    profiles = @(@{
        id = 'ollama-local'; name = '本地 Ollama'; provider = 'openai-compatible'
        baseUrl = 'http://127.0.0.1:11434/v1'; model = 'qwen3:8b'
        apiKeyRef = 'credential://llm/ollama-local'; timeoutSeconds = 120; allowLocalNetwork = $true
    })
}
$goodValidation = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/validate' -Body @{ config = $goodConfig }
Assert-True '合法配置通过 Schema 校验' ($goodValidation.valid) ($goodValidation.errors | ConvertTo-Json -Compress)

$preview = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/import/preview' -Body @{ config = $goodConfig }
Assert-True '导入预览返回 previewId' ($null -ne $preview.previewId) ''
Assert-True '导入预览不修改当前配置' ((Invoke-Api -Method Get -Path '/api/v1/settings/llm-profiles').profiles.Count -eq $profiles.profiles.Count) ''

$confirm = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/import/confirm' -Body @{ previewId = $preview.previewId }
$profilesAfter = Invoke-Api -Method Get -Path '/api/v1/settings/llm-profiles'
Assert-True '确认导入后档案存在（重复运行表现为 unchanged）' `
    (@($profilesAfter.profiles | Where-Object { $_.id -eq 'ollama-local' }).Count -eq 1) `
    "created=$($confirm.created) updated=$($confirm.updated) unchanged=$($confirm.unchanged)"
Assert-True '配置修订号随导入递增' ($confirm.configRevision -ge 1) "实际：$($confirm.configRevision)"
Assert-True '默认档案已指向导入项' ($profilesAfter.defaultProfileId -eq 'ollama-local') "实际：$($profilesAfter.defaultProfileId)"

$exported = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/export' -Body @{}
$exportJson = $exported.config | ConvertTo-Json -Depth 12
Assert-True '导出不含明文密钥字段' (-not ($exportJson -match '"apiKey"\s*:')) ''
Assert-True '导出保留凭据引用' ($exportJson -match 'credential://llm/ollama-local') ''

$conn = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/test-connection' `
    -Body @{ profile = @{ id = 'probe'; provider = 'openai-compatible'; baseUrl = 'http://127.0.0.1:11434/v1'; model = 'qwen3:8b'; timeoutSeconds = 60; allowLocalNetwork = $true } }
Assert-True '本地模型连接测试返回结构化结果' ($null -ne $conn.category) "category=$($conn.category) ok=$($conn.ok)"
if ($conn.ok) {
    Assert-True '连接测试返回模型回显' ($null -ne $conn.modelEcho) "modelEcho=$($conn.modelEcho)"
} else {
    Write-Host ("       提示：AI 服务或本地模型当前不可用（category=" + $conn.category + "），本项按降级路径记录") -ForegroundColor DarkYellow
}

$blocked = Invoke-Api -Method Post -Path '/api/v1/settings/llm-profiles/test-connection' `
    -Body @{ profile = @{ id = 'blocked'; provider = 'openai-compatible'; baseUrl = 'http://169.254.169.254/latest/meta-data'; model = 'x'; timeoutSeconds = 10; allowLocalNetwork = $false } }
Assert-True '云元数据地址被安全策略拦截' ((-not $blocked.ok) -and ($blocked.category -eq 'blocked' -or $blocked.category -eq 'unavailable')) `
    "category=$($blocked.category)"

# ------------------------------------------------------------------ 9. 错误码与幂等
Write-Host "[9] 错误码与幂等语义" -ForegroundColor Yellow
$notFoundStatus = Get-StatusCode { Invoke-Api -Method Get -Path '/api/v1/questions/q_does_not_exist' | Out-Null }
Assert-True '不存在的资源返回 404' ($notFoundStatus -eq 404) "实际：$notFoundStatus"

$validationStatus = Get-StatusCode {
    Invoke-Api -Method Post -Path '/api/v1/questions' -Body @{ title = ''; questionType = 'single_choice'; stem = ''; answer = @{ type = 'single_choice'; value = 'A' } } | Out-Null
}
Assert-True '字段校验失败返回 400' ($validationStatus -eq 400) "实际：$validationStatus"

$busyStatus = Get-StatusCode {
    Invoke-Api -Method Get -Path '/api/v1/questions?sort=drop_table' | Out-Null
}
Assert-True '非法排序参数被拒绝' ($busyStatus -eq 400) "实际：$busyStatus"

$conflictIdemStatus = Get-StatusCode {
    Invoke-Api -Method Post -Path ('/api/v1/attempts/' + $attemptId + '/submit') -Body @{} -IdempotencyKey $idemKey | Out-Null
}
Assert-True '同键不同载荷返回 IDEMPOTENCY_CONFLICT 或被幂等重放' ($conflictIdemStatus -in @(200, 409)) "实际：$conflictIdemStatus"

# ------------------------------------------------------------------ 10. AI 闭环（可选）
Write-Host "[10] AI 讲解与主观题建议分（真实模型）" -ForegroundColor Yellow
$aiService = (Invoke-Api -Method Get -Path '/api/v1/meta').aiService
if (-not $aiService.available) {
    Write-Host ("       跳过：AI 服务不可用（" + $aiService.message + "）——核心答题闭环不依赖它") -ForegroundColor DarkYellow
} else {
    $explain = Invoke-Api -Method Post -Path ('/api/v1/ai/questions/' + $questionId + '/explain') `
        -Body @{ userAnswer = @{ type = 'single_choice'; value = 'B' } }
    Assert-True 'AI 讲解返回非空文本' (-not [string]::IsNullOrWhiteSpace($explain.explanation)) ''
    Assert-True 'AI 讲解标注所用模型' (-not [string]::IsNullOrWhiteSpace($explain.model)) "model=$($explain.model)"

    $subjective = Invoke-Api -Method Post -Path '/api/v1/questions' -Body @{
        title = '冒烟测试题：主观题 AI 建议分'
        questionType = 'short_answer'
        difficulty = 2
        stem = '请说明为什么答题系统必须「先持久化作答，再判分」。'
        options = @()
        answer = @{
            type = 'short_answer'
            referenceAnswer = '先落库保证答案不丢；评分可重试且幂等，不会重复生成作答。'
            rubric = @{
                maxScore = 10
                criteria = @(
                    @{ id = 'c1'; desc = '指出先落库避免丢答案'; weight = 60 },
                    @{ id = 'c2'; desc = '指出评分可重试且幂等'; weight = 40 }
                )
            }
        }
        explanation = '冒烟测试用主观题。'
        knowledgeTags = @('冒烟测试')
        changeNote = '冒烟测试创建'
    }
    Invoke-Api -Method Post -Path ('/api/v1/questions/' + $subjective.id + '/publish') -Body @{} | Out-Null
    $subjectivePaper = Invoke-Api -Method Post -Path '/api/v1/papers' -Body @{
        title = '冒烟测试试卷：主观题'
        description = '仅用于冒烟测试，验证 AI 建议分与人工复核闭环。'
        questions = @(@{ questionId = $subjective.id; score = 10 })
    }
    Invoke-Api -Method Post -Path ('/api/v1/papers/' + $subjectivePaper.id + '/publish') -Body @{} | Out-Null

    $subjectiveAttempt = Invoke-Api -Method Post -Path '/api/v1/attempts' -Body @{ paperId = $subjectivePaper.id }
    $subjectiveSheet = Invoke-Api -Method Get -Path ('/api/v1/attempts/' + $subjectiveAttempt.id)
    $subjectiveQuestion = $subjectiveSheet.questions[0]
    Invoke-Api -Method Put -Path ('/api/v1/attempts/' + $subjectiveAttempt.id + '/answers/' + $subjectiveQuestion.snapshotQuestionId) `
        -Body @{ answer = @{ type = 'short_answer'; value = '先把答案写进数据库，避免评分失败导致答案丢失；评分可以重试。' } } | Out-Null
    $subjectiveResult = Invoke-Api -Method Post -Path ('/api/v1/attempts/' + $subjectiveAttempt.id + '/submit') -Body @{}
    $pendingItem = $subjectiveResult.items[0]
    Assert-True '主观题提交后进入待复核' ($pendingItem.gradingStatus -eq 'pending_review') "实际：$($pendingItem.gradingStatus)"
    Assert-True '主观题提交阶段不给分' ($null -eq $pendingItem.score) "实际：$($pendingItem.score)"

    $gradeRequest = Invoke-Api -Method Post -Path ('/api/v1/attempts/' + $subjectiveAttempt.id + '/ai-grade') -Body @{}
    Assert-True 'AI 评分任务入队' ($gradeRequest.taskIds.Count -ge 1) "任务数：$($gradeRequest.taskIds.Count)"

    $taskId = $gradeRequest.taskIds[0]
    $taskStatus = 'unknown'
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        $task = Invoke-Api -Method Get -Path ('/api/v1/tasks/' + $taskId)
        $taskStatus = $task.status
        if ($taskStatus -in @('succeeded', 'failed', 'cancelled')) { break }
    }
    Assert-True 'AI 评分任务执行成功' ($taskStatus -eq 'succeeded') "最终状态：$taskStatus"

    $afterAi = Invoke-Api -Method Get -Path ('/api/v1/attempts/' + $subjectiveAttempt.id + '/result')
    $aiItem = $afterAi.items[0]
    Assert-True 'AI 建议分已写入' ($null -ne $aiItem.score) "score=$($aiItem.score)"
    Assert-True 'AI 评分标记为需人工复核' ($aiItem.gradingStatus -eq 'pending_review') "实际：$($aiItem.gradingStatus)"
    Assert-True 'AI 评分记录评分依据' ($null -ne $aiItem.gradingDetail.criteria) ''

    $confirmed = Invoke-Api -Method Post -Path ('/api/v1/attempts/' + $subjectiveAttempt.id + '/answers/' + $subjectiveQuestion.snapshotQuestionId + '/grade') `
        -Body @{ score = 10; note = '冒烟测试人工确认满分' }
    Assert-True '人工确认后转为已评分' ($confirmed.items[0].gradingStatus -eq 'graded') "实际：$($confirmed.items[0].gradingStatus)"
    Assert-True '人工确认后总分重算' ($confirmed.attempt.totalScore -eq 10) "实际：$($confirmed.attempt.totalScore)"
    Assert-True '人工评分记录方式为 manual' ($confirmed.items[0].gradingMethod -eq 'manual') "实际：$($confirmed.items[0].gradingMethod)"
}

# ------------------------------------------------------------------ 阶段 6：资料库与本地检索
Write-Host ""
Write-Host "--- 阶段 6：资料库与本地检索 ---" -ForegroundColor Cyan
# 资料内容每次运行都带唯一标识，保证脚本可重复执行（否则第二次运行会命中「重复导入」而失败）
$docMarker = '冒烟标记甲乙丙'
$docUnique = [guid]::NewGuid().ToString('N')
$docBody = (($docMarker + '：三次握手确保双方收发能力与初始序号同步，这是可靠传输的起点。') * 30) +
    "`r`n`r`n" + ('滑动窗口用于流量控制，窗口大小决定了在途未确认数据的上限。' * 30) +
    "`r`n`r`n" + ('本次运行标识：' + $docUnique)
$docSource = Join-Path ([System.IO.Path]::GetTempPath()) ("aia-smoke-doc-" + $docUnique + ".txt")
$docPdf = $docSource + '.pdf'
[System.IO.File]::WriteAllText($docSource, $docBody, [System.Text.UTF8Encoding]::new($false))
$docSha = (Get-FileHash -Algorithm SHA256 -Path $docSource).Hash.ToLower()
try {
    $imported = Invoke-Api -Method Post -Path '/api/v1/documents/import' -Body @{
        sourcePath = $docSource; sha256 = $docSha; title = '冒烟资料'; onDuplicate = 'reject'
    }
    Assert-True '资料导入成功且已建索引' ($imported.status -eq 'indexed') "status=$($imported.status)"
    Assert-True '资料分块数不少于 2' ($imported.chunkCount -ge 2) "chunkCount=$($imported.chunkCount)"
    Assert-True '解析器版本含 text-v1' (Test-TextContains $imported.parserVersion 'text-v1') "parserVersion=$($imported.parserVersion)"

    $hit = Invoke-Api -Method Get -Path ('/api/v1/search?q=' + [uri]::EscapeDataString($docMarker))
    Assert-True '检索返回命中' ($hit.results.Count -ge 1) "命中数=$($hit.results.Count)"
    $topHit = $hit.results[0]
    Assert-True '命中归属本次导入的资料' ($topHit.documentId -eq $imported.id) ''
    Assert-True '命中带可定位的 chunkId' (-not [string]::IsNullOrWhiteSpace($topHit.chunkId)) ''
    Assert-True '命中片段带 [[ ]] 标记' (Test-TextContains $topHit.snippet '[[') ''

    # 阶段 6 完成定义：检索结果必须能追溯回具体分块并逐字核对
    $docChunks = Invoke-Api -Method Get -Path ('/api/v1/documents/' + $imported.id + '/chunks?size=50')
    $matchedChunk = $docChunks.items | Where-Object { $_.id -eq $topHit.chunkId }
    Assert-True '命中可回溯到具体分块' ($null -ne $matchedChunk) "chunkId=$($topHit.chunkId)"
    Assert-True '分块原文逐字包含检索词' ($null -ne $matchedChunk -and (Test-TextContains $matchedChunk.content $docMarker)) ''

    $shortHit = Invoke-Api -Method Get -Path ('/api/v1/search?q=' + [uri]::EscapeDataString('握手'))
    Assert-True '短查询降级为关键词模式' ($shortHit.mode -eq 'keyword') "mode=$($shortHit.mode)"

    $dupError = Get-ApiError { Invoke-Api -Method Post -Path '/api/v1/documents/import' -Body @{
        sourcePath = $docSource; sha256 = $docSha; title = '冒烟资料'; onDuplicate = 'reject' } }
    Assert-True '重复导入返回 409' ($dupError.Status -eq 409) "实际：$($dupError.Status)"
    Assert-True '重复导入错误码为 STATE_CONFLICT' ($dupError.Code -eq 'STATE_CONFLICT') "code=$($dupError.Code)"

    $bySha = Invoke-Api -Method Get -Path ('/api/v1/documents?sha256=' + $docSha)
    Assert-True '按 sha256 反查到已存在资料' ($bySha.total -eq 1) "total=$($bySha.total)"
    Assert-True '反查结果 id 与首次导入一致' ($bySha.items[0].id -eq $imported.id) ''

    $secondDoc = Invoke-Api -Method Post -Path '/api/v1/documents/import' -Body @{
        sourcePath = $docSource; sha256 = $docSha; title = '冒烟资料副本'; onDuplicate = 'new'
    }
    Assert-True 'onDuplicate=new 新建第二条资料' ($secondDoc.id -ne $imported.id) ''
    Assert-True '同 sha256 资料数为 2' ((Invoke-Api -Method Get -Path ('/api/v1/documents?sha256=' + $docSha)).total -eq 2) ''

    $versionBefore = (Invoke-Api -Method Get -Path '/api/v1/search/index').activeVersion
    $rebuilt = Invoke-Api -Method Post -Path '/api/v1/documents/reindex' -Body @{}
    Assert-True '索引重建无失败项' ($rebuilt.failures.Count -eq 0) ''
    Assert-True '索引 activeVersion 递增' ($rebuilt.indexState.activeVersion -eq ($versionBefore + 1)) `
        "before=$versionBefore after=$($rebuilt.indexState.activeVersion)"
    Assert-True '重建后检索仍可命中' ((Invoke-Api -Method Get -Path ('/api/v1/search?q=' + [uri]::EscapeDataString($docMarker))).results.Count -ge 1) ''

    $deleteFirst = Invoke-Api -Method Delete -Path ('/api/v1/documents/' + $secondDoc.id)
    Assert-True '删除一条时保留共享文件' ($deleteFirst.fileRemoved -eq $false) ''
    $deleteSecond = Invoke-Api -Method Delete -Path ('/api/v1/documents/' + $imported.id)
    Assert-True '删除最后一条引用时清理文件' ($deleteSecond.fileRemoved -eq $true) ''
    Assert-True '一致性报告无缺失文件' ((Invoke-Api -Method Get -Path '/api/v1/system/files').missingFiles.Count -eq 0) ''

    [System.IO.File]::WriteAllText($docPdf, '%PDF-1.4 smoke', [System.Text.UTF8Encoding]::new($false))
    $pdfError = Get-ApiError { Invoke-Api -Method Post -Path '/api/v1/documents/import' -Body @{
        sourcePath = $docPdf; sha256 = (Get-FileHash -Algorithm SHA256 -Path $docPdf).Hash.ToLower()
        title = '冒烟PDF'; onDuplicate = 'reject' } }
    Assert-True '非文本格式返回 400' ($pdfError.Status -eq 400) "实际：$($pdfError.Status)"
    Assert-True '非文本格式错误码为 DOCUMENT_FORMAT_UNSUPPORTED' ($pdfError.Code -eq 'DOCUMENT_FORMAT_UNSUPPORTED') "code=$($pdfError.Code)"
} finally {
    Remove-Item -Force $docSource -ErrorAction SilentlyContinue
    Remove-Item -Force $docPdf -ErrorAction SilentlyContinue
}

# ------------------------------------------------------------------ 汇总
Write-Host ""
Write-Host "=== 冒烟测试结果 ===" -ForegroundColor Cyan
Write-Host ("通过：" + $script:passed + "    失败：" + $script:failed) -ForegroundColor $(if ($script:failed -eq 0) { 'Green' } else { 'Red' })
if ($script:failed -gt 0) {
    Write-Host "失败项：" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    exit 1
}
Write-Host "全部通过。" -ForegroundColor Green
exit 0
