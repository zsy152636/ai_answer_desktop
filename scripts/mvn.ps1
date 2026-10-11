# 解析并调用 Maven（JDK 17）。
# 优先使用 PATH 上的 mvn；否则复用 ~/.m2/wrapper/dists 中已下载的 Maven 发行版；
# 再否则给出明确的手工安装指引，而不是静默失败。
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/mvn.ps1 compile
#   powershell -ExecutionPolicy Bypass -File scripts/mvn.ps1 test
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaApi = Join-Path $repoRoot 'services\java-api'

# java -version 会往 stderr 输出；在 Windows PowerShell 5.1 下若把错误偏好调成
# SilentlyContinue，2>&1 合并过来的记录会被直接吞掉（探测结果为空），因此统一经 cmd 捕获。
function Get-JavaMajorVersion([string]$javaExe) {
    $line = (cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1)
    if ("$line" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Resolve-JavaHome {
    if ($env:JAVA_HOME) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if ((Test-Path $java) -and (Get-JavaMajorVersion $java) -ge 17) { return $env:JAVA_HOME }
    }
    $candidates = @()
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdkRoot) {
        $candidates += Get-ChildItem $jdkRoot -Directory | Where-Object { $_.Name -match '17|2[1-9]' } |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    }
    foreach ($dir in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
        if (Test-Path $dir) { $candidates += (Get-ChildItem $dir -Directory | ForEach-Object { $_.FullName }) }
    }
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin\java.exe'
        if ((Test-Path $java) -and (Get-JavaMajorVersion $java) -ge 17) { return $candidate }
    }
    throw "未找到 JDK 17+。请设置 JAVA_HOME 指向 JDK 17 及以上版本（Spring Boot 3 需要）。"
}

function Resolve-Maven {
    $onPath = Get-Command mvn -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $dists = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
    if (Test-Path $dists) {
        # 目录层级：dists/<artifact>-bin/<hash>/apache-maven-x.y.z/bin
        $candidates = Get-ChildItem $dists -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'apache-maven-*' } |
            Sort-Object Name -Descending
        foreach ($dist in $candidates) {
            $cmd = Join-Path $dist.FullName 'bin\mvn.cmd'
            if (Test-Path $cmd) { return $cmd }
        }
    }
    throw "未找到 Maven。请安装 Maven 3.9+（或运行任意带 Maven Wrapper 的项目预取发行版）后重试。"
}

$env:JAVA_HOME = Resolve-JavaHome
$mvn = Resolve-Maven
Write-Host "[mvn] JAVA_HOME=$env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "[mvn] maven=$mvn" -ForegroundColor DarkGray

$pomArgs = @('-B', '-ntp', '-f', (Join-Path $javaApi 'pom.xml'))
if ($MavenArgs.Count -eq 0) { $MavenArgs = @('package') }

# Maven/JVM 会把警告写到 stderr（例如 CDS sharing 提示）。在 ErrorActionPreference=Stop 下
# 这些 stderr 记录会被当成终止错误，导致命令还没跑完脚本就退出，因此本地临时降级。
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $mvn @pomArgs @MavenArgs
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
exit $exitCode
