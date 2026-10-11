# Add a UTF-8 BOM to every PowerShell script in the repository.
#
# Why: Windows PowerShell 5.1 reads .ps1 files as ANSI (GBK on zh-CN systems) unless the
# file starts with a UTF-8 BOM. Scripts containing Chinese text then fail to parse.
# Editors and patch tools that rewrite these files silently drop the BOM, so run this
# after editing any .ps1 (it is idempotent and leaves already-correct files untouched).
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$fixed = 0
$scanned = 0

Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.ps1' |
    Where-Object { $_.FullName -notmatch '\\(node_modules|\.venv|target|dist|\.git)\\' } |
    ForEach-Object {
        $scanned++
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        if ($hasBom) { return }
        $text = [System.IO.File]::ReadAllText($_.FullName, [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::WriteAllText($_.FullName, $text, $utf8Bom)
        $fixed++
        Write-Host ("BOM added: " + $_.FullName)
    }

Write-Host ("scanned " + $scanned + " file(s), fixed " + $fixed + ".")
exit 0
