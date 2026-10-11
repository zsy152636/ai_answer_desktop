# 以「编码一致」的方式启动桌面应用（推荐入口）。
#
# 背景：应用与两个子进程统一按 UTF-8 输出（Python 侧强制 PYTHONIOENCODING=utf-8，
# Java 侧 logback 的 CONSOLE appender 声明 UTF-8）。但 Windows 中文环境里读日志的一端
# 经常按 GBK 解码，于是中文全变乱码——例如 UTF-8 的「中文日志」被按 GBK 解码会显示成
# 「涓枃鏃ュ織」。下面三件事必须一起做，只做其中一件仍会乱码：
#   1) chcp 65001           —— 控制台代码页：决定直接写字节的程序如何被解释；
#   2) [Console]::OutputEncoding —— PowerShell 解码「原生命令输出」所用的编码
#      （PowerShell 5.1 在启动时就缓存了它，所以只 chcp 对它无效）；
#   3) $OutputEncoding      —— PowerShell 发给原生命令的编码。
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/start-app.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/start-app.ps1 -NoBuild   # 跳过 tsc
[CmdletBinding()]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$desktopDir = Join-Path $repoRoot 'apps\desktop'

if (-not (Test-Path $desktopDir)) { throw "未找到 $desktopDir" }

# 1) 控制台代码页（chcp.com 必须继承当前控制台才有效，不要加 windowsHide/CREATE_NO_WINDOW）
try { & chcp.com 65001 | Out-Null } catch { Write-Host '切换控制台代码页失败，继续启动' -ForegroundColor DarkYellow }

# 2) / 3) PowerShell 自身的编码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 子进程输出编码（桌面壳也会自行设置，这里保证独立启动 Python/Java 时同样一致）
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'

Write-Host '编码已对齐：控制台 65001 / PowerShell UTF-8' -ForegroundColor DarkGray

Push-Location $desktopDir
try {
    if ($NoBuild) {
        & pnpm start:only
    } else {
        & pnpm start
    }
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $code
