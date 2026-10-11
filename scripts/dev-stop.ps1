# 停止由 dev-start.ps1 启动的进程。
#
# 只按 .runtime/pids.json 中记录的 PID 停止，不做「按进程名全杀」，
# 避免误杀用户机器上其他 java / python / node 进程。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts/dev-stop.ps1 [-Force]
[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot '.runtime'
$pidFile = Join-Path $runtimeDir 'pids.json'

if (-not (Test-Path $pidFile)) {
    Write-Host '未找到 .runtime/pids.json：没有由 dev-start.ps1 启动的进程记录。' -ForegroundColor DarkYellow
    exit 0
}

$record = Get-Content -Raw -Encoding UTF8 -LiteralPath $pidFile | ConvertFrom-Json
$stopped = @()
foreach ($name in @('renderer', 'java', 'python')) {
    $processId = $record.$name
    if (-not $processId) { continue }
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host ("  " + $name + " (PID " + $processId + ") 已不在运行") -ForegroundColor DarkGray
        continue
    }
    try {
        if ($Force) {
            Stop-Process -Id $processId -Force -ErrorAction Stop
        } else {
            Stop-Process -Id $processId -ErrorAction Stop
        }
        $stopped += $name
        Write-Host ("  已停止 " + $name + " (PID " + $processId + ")") -ForegroundColor Green
    } catch {
        Write-Host ("  停止 " + $name + " 失败：" + $_.Exception.Message) -ForegroundColor Red
    }
}

Remove-Item -Force (Join-Path $runtimeDir 'java-endpoint.json') -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $runtimeDir 'python-endpoint.json') -ErrorAction SilentlyContinue
Remove-Item -Force $pidFile -ErrorAction SilentlyContinue

Write-Host ("已停止：" + ($(if ($stopped.Count -gt 0) { $stopped -join ', ' } else { '无' }))) -ForegroundColor Cyan
Write-Host '注意：令牌随进程退出即失效；下次启动会重新生成。' -ForegroundColor DarkGray
exit 0
