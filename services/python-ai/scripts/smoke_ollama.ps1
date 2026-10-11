# 真实启动 + 真实 Ollama 端到端验证脚本（验证后自动停止进程）
# 用法（PowerShell 执行策略受限时）：
#   $sb = [scriptblock]::Create((Get-Content -Raw -Encoding UTF8 .\scripts\smoke_ollama.ps1)); & $sb
# 产物：<服务目录>\.smoke-endpoint.json（验证结束自动删除）、<服务目录>\smoke-report.txt
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$serviceDir = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
$python = Join-Path $serviceDir '.venv\Scripts\python.exe'
$endpointFile = Join-Path $serviceDir '.smoke-endpoint.json'
$stdout = Join-Path $serviceDir 'smoke-stdout.log'
$stderr = Join-Path $serviceDir 'smoke-stderr.log'
$report = Join-Path $serviceDir 'smoke-report.txt'
foreach ($f in @($endpointFile, $stdout, $stderr, $report)) {
    if (Test-Path $f) { Remove-Item $f -Force }
}

$script:actualPort = 0
$script:lines = New-Object System.Collections.Generic.List[string]
function Say([string]$text) {
    Write-Host $text
    $script:lines.Add($text)
}

# 注意：Start-Process 的 -ArgumentList 不做引号转义，路径含空格/中文会被拆开；
# Windows PowerShell 5.1 的 ProcessStartInfo 没有 ArgumentList，因此手工按
# Windows 命令行规则加引号。
function Quote-Arg([string]$value) {
    return '"' + ($value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}
$argList = @('-m', 'app.main', '--port', '0', '--endpoint-file', $endpointFile, '--log-level', 'INFO')
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $python
$psi.WorkingDirectory = $serviceDir
$psi.UseShellExecute = $false
$psi.Arguments = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$proc = [System.Diagnostics.Process]::Start($psi)
$outTask = $proc.StandardOutput.ReadToEndAsync()
$errTask = $proc.StandardError.ReadToEndAsync()

try {
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $endpointFile)) {
        if ((Get-Date) -gt $deadline) {
            Say 'FAILED: 端点文件未生成'
            Say ("stdout: " + (Get-Content $stdout -Raw -ErrorAction SilentlyContinue))
            Say ("stderr: " + (Get-Content $stderr -Raw -ErrorAction SilentlyContinue))
            throw '端点文件未生成，进程可能启动失败'
        }
        if ($proc.HasExited) {
            Say ("FAILED: 进程已退出 exit=" + $proc.ExitCode)
            Say ("stderr: " + (Get-Content $stderr -Raw -ErrorAction SilentlyContinue))
            throw "进程已退出，exit=$($proc.ExitCode)"
        }
        Start-Sleep -Milliseconds 200
    }
    $ep = Get-Content $endpointFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $script:actualPort = [int]$ep.port
    Say '=== endpoint 文件 ==='
    Say ($ep | ConvertTo-Json -Compress)
    Say "tokenHeader=$($ep.tokenHeader) pid=$($ep.pid) startedAt=$($ep.startedAt) tokenLength=$($ep.token.Length)"

    Say ''
    Say '=== GET /health (无令牌) ==='
    $health = Invoke-RestMethod "http://127.0.0.1:$script:actualPort/health" -TimeoutSec 10
    Say ($health | ConvertTo-Json -Compress)

    $headers = @{ 'X-Internal-Token' = $ep.token }

    Say ''
    Say '=== 无令牌访问 /api/v1/ai/explain（应 401） ==='
    try {
        Invoke-RestMethod -Method Post "http://127.0.0.1:$script:actualPort/api/v1/ai/explain" -ContentType 'application/json' -Body '{}' -TimeoutSec 10 | Out-Null
        Say 'UNEXPECTED: 未返回 401'
    }
    catch {
        $resp = $_.Exception.Response
        Say ("HTTP " + [int]$resp.StatusCode)
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        Say $reader.ReadToEnd()
    }

    $profile = @{
        id                    = 'ollama-local'
        provider              = 'openai-compatible'
        baseUrl               = 'http://127.0.0.1:11434/v1'
        model                 = 'qwen3:8b'
        timeoutSeconds        = 180
        connectTimeoutSeconds = 5
        allowLocalNetwork     = $true
        parameters            = @{ temperature = 0.2; maxTokens = 1024 }
        apiKey                = 'local-ollama'
    }

    Say ''
    Say '=== POST /api/v1/ai/test-connection（本地 Ollama，allowLocalNetwork=true） ==='
    $tc = Invoke-RestMethod -Method Post "http://127.0.0.1:$script:actualPort/api/v1/ai/test-connection" -Headers $headers -ContentType 'application/json' -Body (@{ profile = $profile } | ConvertTo-Json -Depth 8) -TimeoutSec 60
    Say ($tc | ConvertTo-Json -Depth 6 -Compress)

    Say ''
    Say '=== GET /api/v1/ai/profiles/ollama-local/probe（不使用密钥） ==='
    $probeUrl = "http://127.0.0.1:$script:actualPort/api/v1/ai/profiles/ollama-local/probe?baseUrl=http%3A%2F%2F127.0.0.1%3A11434%2Fv1&allowLocalNetwork=true"
    $probe = Invoke-RestMethod $probeUrl -Headers $headers -TimeoutSec 60
    Say ($probe | ConvertTo-Json -Depth 6 -Compress)

    Say ''
    Say '=== POST /api/v1/ai/explain（真实 qwen3:8b） ==='
    $explainBody = @{
        question   = @{
            stem         = 'TCP 建立连接需要几次握手？'
            questionType = 'single_choice'
            options      = @(
                @{ key = 'A'; content = '1 次'; correct = $false },
                @{ key = 'B'; content = '2 次'; correct = $false },
                @{ key = 'C'; content = '3 次'; correct = $true }
            )
            answer       = @{ type = 'single_choice'; value = 'C' }
            explanation  = '三次握手确保双方收发能力与初始序号同步。'
        }
        userAnswer = @{ type = 'single_choice'; value = 'B' }
        profile    = $profile
        locale     = 'zh-CN'
    }
    $ex = Invoke-RestMethod -Method Post "http://127.0.0.1:$script:actualPort/api/v1/ai/explain" -Headers $headers -ContentType 'application/json' -Body ($explainBody | ConvertTo-Json -Depth 10) -TimeoutSec 600
    Say "success=$($ex.success) model=$($ex.data.model) latencyMs=$($ex.data.latencyMs) profileRef=$($ex.data.profileRef) tipsCount=$($ex.data.tips.Count) explanationLength=$($ex.data.explanation.Length) requestId=$($ex.requestId)"
    Say "usage: $($ex.data.usage | ConvertTo-Json -Compress)"
    Say "explanation(前 400 字): $($ex.data.explanation.Substring(0, [Math]::Min(400, $ex.data.explanation.Length)))"
    Say "tips: $($ex.data.tips | ConvertTo-Json -Compress)"

    Say ''
    Say '=== POST /api/v1/ai/grade-subjective（真实 qwen3:8b） ==='
    $gradeBody = @{
        question   = @{
            stem            = '请说明 TCP 三次握手的过程与目的。'
            referenceAnswer = '客户端发送 SYN，服务端回应 SYN/ACK，客户端再发送 ACK；目的是同步双方初始序号并确认收发能力。'
            rubric          = @{
                criteria = @(
                    @{ id = 'c1'; desc = '指出三次握手顺序'; weight = 60 },
                    @{ id = 'c2'; desc = '说明握手目的'; weight = 40 }
                )
                maxScore = 10
            }
        }
        userAnswer = '客户端先发 SYN，服务端回 SYN/ACK，客户端再发 ACK，用来确认双方都能收发数据。'
        profile    = $profile
        maxScore   = 10
    }
    $gr = Invoke-RestMethod -Method Post "http://127.0.0.1:$script:actualPort/api/v1/ai/grade-subjective" -Headers $headers -ContentType 'application/json' -Body ($gradeBody | ConvertTo-Json -Depth 10) -TimeoutSec 600
    Say "success=$($gr.success) suggestedScore=$($gr.data.suggestedScore)/$($gr.data.maxScore) confidence=$($gr.data.confidence) needsReview=$($gr.data.needsReview) model=$($gr.data.model) latencyMs=$($gr.data.latencyMs) requestId=$($gr.requestId)"
    Say "criteria: $($gr.data.criteria | ConvertTo-Json -Depth 5 -Compress)"

    Say '先停止服务进程，再读取日志'
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force
        Start-Sleep -Milliseconds 800
    }
    Say ''
    Say '=== 自定义日志（末尾 20 行，检查是否泄漏令牌/密钥） ==='
    $script:serviceStdout = $outTask.Result
    $script:serviceStderr = $errTask.Result
    Set-Content -Path $stdout -Value $script:serviceStdout -Encoding UTF8
    Set-Content -Path $stderr -Value $script:serviceStderr -Encoding UTF8
    ($script:serviceStdout -split "`r?`n" | Select-Object -Last 20) | ForEach-Object { Say $_ }
    Say ("日志中出现完整内部令牌: " + ($script:serviceStdout -like "*$($ep.token)*"))
    Say ("日志中出现 apiKey: " + ($script:serviceStdout -like '*local-ollama*'))
    Say '=== stderr ==='
    ($script:serviceStderr -split "`r?`n" | Select-Object -Last 10) | ForEach-Object { Say $_ }
}
catch {
    Say "FAILED: $($_.Exception.Message)"
    Say $_.ScriptStackTrace
}
finally {
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force
        Start-Sleep -Milliseconds 800
    }
    Say ''
    Say "[进程清理] pid=$($proc.Id) HasExited=$($proc.HasExited)"
    if ($script:actualPort -gt 0) {
        $still = Get-NetTCPConnection -LocalPort $script:actualPort -State Listen -ErrorAction SilentlyContinue
        if ($still) { Say "WARNING: 端口 $script:actualPort 仍被监听" } else { Say "端口 $script:actualPort 已释放" }
    }
    if (Test-Path $endpointFile) { Remove-Item $endpointFile -Force }
    Set-Content -Path $report -Value $script:lines -Encoding UTF8
    Write-Host "报告文件: $report"
}
