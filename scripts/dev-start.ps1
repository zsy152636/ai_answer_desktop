# 一键启动开发环境（Python AI 服务 → Java 业务服务 → 可选前端 Vite 开发服务器）。
#
# 设计要点：
#   * 所有运行期文件集中在 <仓库根>/.runtime/，与生产环境（Electron 的 userData 目录）语义一致；
#   * 数据目录默认 <仓库根>/.devdata/java，可用 -DataDir 覆盖；
#   * 进程 PID 写入 .runtime/pids.json，dev-stop.ps1 据此精确停止，不误杀其他 java/python；
#   * 启动顺序不可颠倒：Java 启动时就要读取 Python 端点文件（见 docs/architecture/decisions.md ADR-002）。
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/dev-start.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/dev-start.ps1 -WithUI -RebuildJava
[CmdletBinding()]
param(
    [switch]$WithUI,
    [switch]$RebuildJava,
    [int]$JavaPort = 18765,
    [int]$UiPort = 5173,
    [string]$DataDir,
    [switch]$SkipPython
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot '.runtime'
if (-not $DataDir) { $DataDir = Join-Path $repoRoot '.devdata\java' }
$javaEndpoint = Join-Path $runtimeDir 'java-endpoint.json'
$pythonEndpoint = Join-Path $runtimeDir 'python-endpoint.json'
$pidFile = Join-Path $runtimeDir 'pids.json'
$logDir = Join-Path $runtimeDir 'logs'

function Resolve-JavaExe {
    if ($env:AIA_JAVA_BIN -and (Test-Path $env:AIA_JAVA_BIN)) { return $env:AIA_JAVA_BIN }
    # 注意：不能把变量命名为 $home —— 它是 PowerShell 的只读自动变量（大小写不敏感）
    $javaHome = $env:JAVA_HOME
    if ($javaHome) {
        $candidate = Join-Path $javaHome 'bin\java.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdkRoot) {
        foreach ($dir in (Get-ChildItem $jdkRoot -Directory | Sort-Object Name -Descending)) {
            $candidate = Join-Path $dir.FullName 'bin\java.exe'
            if (Test-Path $candidate) {
                $line = (cmd /c "`"$candidate`" -version 2>&1" | Select-Object -First 1)
                if ("$line" -match '"(\d+)' -and [int]$Matches[1] -ge 17) { return $candidate }
            }
        }
    }
    throw '未找到 JDK 17+。请设置 JAVA_HOME 指向 JDK 17 及以上版本。'
}

function Test-EndpointReady([string]$file, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $file) {
            try {
                $json = Get-Content -Raw -Encoding UTF8 -LiteralPath $file | ConvertFrom-Json
                if ($json.baseUrl) { return $json }
            } catch {
                # 文件正在被写入，稍后重试
            }
        }
        Start-Sleep -Milliseconds 400
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $runtimeDir, $logDir, $DataDir | Out-Null
Remove-Item -Force $javaEndpoint, $pythonEndpoint, $pidFile -ErrorAction SilentlyContinue

$pids = [ordered]@{}

# ------------------------------------------------------------------ Java 构建
$jar = Join-Path $repoRoot 'services\java-api\target\answer-java-api.jar'
if ($RebuildJava -or -not (Test-Path $jar)) {
    Write-Host '[1/4] 构建 Java 业务服务（首次执行会下载依赖）…' -ForegroundColor Yellow
    & (Join-Path $PSScriptRoot 'mvn.ps1') package '-DskipTests' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Java 服务构建失败，请检查上面的 Maven 输出。' }
} else {
    Write-Host '[1/4] 复用已有 Java 构建产物（需要重新构建请加 -RebuildJava）' -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ Python AI 服务
if (-not $SkipPython) {
    $pythonExe = Join-Path $repoRoot 'services\python-ai\.venv\Scripts\python.exe'
    if (Test-Path $pythonExe) {
        Write-Host '[2/4] 启动 Python AI 服务…' -ForegroundColor Yellow
        # Start-Process 的 -ArgumentList 只是把数组用空格拼起来，不负责加引号；
        # 仓库路径含空格（"学习伙伴 Agent"），所以含空格的取值必须自己用双引号包起来。
        # 日志重定向到文件时 Windows 上的 Python 默认用 ANSI 代码页（中文环境 GBK），
        # 会把 .runtime/logs/python-ai.*.log 写成乱码；强制 UTF-8 与其余日志保持一致。
        $env:PYTHONIOENCODING = 'utf-8'
        $env:PYTHONUTF8 = '1'
        $pythonArgs = "-m app.main --host 127.0.0.1 --port 0 --endpoint-file `"$pythonEndpoint`" --log-level INFO"
        $pythonProcess = Start-Process -FilePath $pythonExe -ArgumentList $pythonArgs `
            -WorkingDirectory (Join-Path $repoRoot 'services\python-ai') -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logDir 'python-ai.out.log') `
            -RedirectStandardError (Join-Path $logDir 'python-ai.err.log')
        $pids['python'] = $pythonProcess.Id
        $pythonReady = Test-EndpointReady $pythonEndpoint 40
        if ($pythonReady) {
            Write-Host ("      AI 服务就绪：" + $pythonReady.baseUrl) -ForegroundColor Green
        } else {
            Write-Host '      AI 服务启动超时（答题主流程不受影响，日志见 .runtime/logs/python-ai.err.log）' -ForegroundColor DarkYellow
        }
    } else {
        Write-Host '[2/4] 跳过 AI 服务：未找到 services\python-ai\.venv' -ForegroundColor DarkYellow
        Write-Host '      安装方式见 services/python-ai/README.md（uv venv --python 3.12 .venv）' -ForegroundColor DarkGray
    }
} else {
    Write-Host '[2/4] 已按参数跳过 Python AI 服务' -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ Java 服务
Write-Host '[3/4] 启动 Java 业务服务…' -ForegroundColor Yellow
$javaExe = Resolve-JavaExe
$javaArgs = "-jar `"$jar`" " +
    "--aia.data-dir=`"$DataDir`" " +
    "--aia.endpoint-file=`"$javaEndpoint`" " +
    "--aia.python-endpoint-file=`"$pythonEndpoint`" " +
    "--server.address=127.0.0.1 " +
    "--server.port=$JavaPort"
$javaProcess = Start-Process -FilePath $javaExe -ArgumentList $javaArgs `
    -WorkingDirectory (Join-Path $repoRoot 'services\java-api') -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir 'java-api.out.log') `
    -RedirectStandardError (Join-Path $logDir 'java-api.err.log')
$pids['java'] = $javaProcess.Id
$javaReady = Test-EndpointReady $javaEndpoint 90
if (-not $javaReady) {
    throw "Java 服务启动超时。请查看 $logDir\java-api.err.log 与 $DataDir\logs\java-api.log"
}
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $health = Invoke-RestMethod -Uri ($javaReady.baseUrl + '/health') -TimeoutSec 3
        if ($health.status -eq 'UP') { $healthy = $true; break }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
if (-not $healthy) { throw 'Java 服务进程已启动但健康检查未通过。' }
Write-Host ("      业务服务就绪：" + $javaReady.baseUrl + "（schema 版本 " + $health.schemaVersion + "）") -ForegroundColor Green

# ------------------------------------------------------------------ 前端
if ($WithUI) {
    Write-Host '[4/4] 启动前端开发服务器…' -ForegroundColor Yellow
    $env:AIA_ENDPOINT_FILE = $javaEndpoint
    $pnpm = (Get-Command pnpm -ErrorAction SilentlyContinue).Source
    if (-not $pnpm) { throw '未找到 pnpm，无法启动前端开发服务器。' }
    $uiProcess = Start-Process -FilePath $pnpm -ArgumentList @('dev', '--port', "$UiPort", '--strictPort') `
        -WorkingDirectory (Join-Path $repoRoot 'apps\renderer') -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir 'renderer.out.log') `
        -RedirectStandardError (Join-Path $logDir 'renderer.err.log')
    $pids['renderer'] = $uiProcess.Id
    Write-Host ("      前端开发服务器：http://127.0.0.1:$UiPort（/__app_runtime 提供会话令牌）") -ForegroundColor Green
} else {
    Write-Host '[4/4] 未启动前端（需要界面请加 -WithUI，或用 Electron：apps/desktop 下 npm start）' -ForegroundColor DarkGray
}

$pids['dataDir'] = $DataDir
$pids['javaBaseUrl'] = $javaReady.baseUrl
$pids | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath $pidFile

Write-Host ''
Write-Host '=== 已启动 ===' -ForegroundColor Cyan
Write-Host ("业务服务   : " + $javaReady.baseUrl + "（令牌见 .runtime/java-endpoint.json）")
if ($pids.Contains('python')) { Write-Host ("AI 服务    : " + $(if ($pythonReady) { $pythonReady.baseUrl } else { '未就绪' })) }
Write-Host ("数据目录   : " + $DataDir)
Write-Host ("日志目录   : " + (Join-Path $DataDir 'logs') + " 与 " + $logDir)
Write-Host ("进程记录   : " + $pidFile)
Write-Host ''
Write-Host '下一步：' -ForegroundColor Cyan
Write-Host '  powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1    # 端到端冒烟测试'
Write-Host '  powershell -ExecutionPolicy Bypass -File scripts/dev-stop.ps1      # 停止全部服务'
exit 0
