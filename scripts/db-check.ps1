# 数据库巡检（只读）
#
# 直接用 Python 内置的 sqlite3 打开业务库，做只读巡检：schema 版本、各表行数、
# 完整性检查、外键检查、日志模式与页面统计。不依赖 sqlite3 CLI，也不写任何数据。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts/db-check.ps1 [-DataDir <路径>]
[CmdletBinding()]
param([string]$DataDir)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $DataDir) { $DataDir = Join-Path $repoRoot '.devdata\java' }
$database = Join-Path $DataDir 'database\app.db'

if (-not (Test-Path $database)) {
    Write-Host ("找不到数据库文件：" + $database) -ForegroundColor Red
    Write-Host '请先启动一次服务（scripts/dev-start.ps1）或用 -DataDir 指定数据目录。' -ForegroundColor Red
    exit 1
}

$candidates = @(
    (Join-Path $repoRoot 'services\python-ai\.venv\Scripts\python.exe'),
    'py'
)
$python = $null
foreach ($candidate in $candidates) {
    if ($candidate -eq 'py') { $python = 'py'; break }
    if (Test-Path $candidate) { $python = $candidate; break }
}
if (-not $python) {
    Write-Host '未找到可用的 Python（services\python-ai\.venv 或 py 启动器）。' -ForegroundColor Red
    exit 1
}

$script = Join-Path $env:TEMP ('aia-db-check-' + [guid]::NewGuid().ToString('N') + '.py')
$pythonCode = @'
import json
import sqlite3
import sys

path = sys.argv[1]
# 只读打开：以 file: URI + mode=ro，避免巡检本身写库或建 WAL 文件
connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
try:
    cursor = connection.cursor()
    report = {}
    report["database"] = path
    report["journal_mode"] = cursor.execute("PRAGMA journal_mode").fetchone()[0]
    report["page_count"] = cursor.execute("PRAGMA page_count").fetchone()[0]
    report["page_size"] = cursor.execute("PRAGMA page_size").fetchone()[0]
    report["foreign_keys_on_this_connection"] = cursor.execute("PRAGMA foreign_keys").fetchone()[0]
    report["integrity_check"] = cursor.execute("PRAGMA integrity_check").fetchone()[0]
    report["foreign_key_violations"] = [row for row in cursor.execute("PRAGMA foreign_key_check").fetchall()]
    try:
        report["schema_version"] = cursor.execute("SELECT MAX(version) FROM schema_version").fetchone()[0]
        report["migrations"] = [
            {"version": row[0], "description": row[1], "applied_at": row[2], "checksum": row[3][:12] + "…"}
            for row in cursor.execute(
                "SELECT version, description, applied_at, checksum FROM schema_version ORDER BY version"
            ).fetchall()
        ]
    except sqlite3.Error as error:
        report["schema_version_error"] = str(error)
    tables = [
        row[0]
        for row in cursor.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
    ]
    counts = {}
    for table in tables:
        try:
            counts[table] = cursor.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
        except sqlite3.Error as error:
            counts[table] = f"error: {error}"
    report["row_counts"] = counts
    print(json.dumps(report, ensure_ascii=False, indent=2))
finally:
    connection.close()
'@
Set-Content -LiteralPath $script -Value $pythonCode -Encoding UTF8

try {
    if ($python -eq 'py') {
        & py -3 $script $database
    } else {
        & $python $script $database
    }
} finally {
    Remove-Item -Force $script -ErrorAction SilentlyContinue
}
