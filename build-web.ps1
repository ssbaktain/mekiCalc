# 컴파일 후 웹 서버 실행. 사용법:
#   .\build-web.ps1            # 빌드 + http://localhost:8080 실행
#   .\build-web.ps1 -Port 9000 # 다른 포트
param([int]$Port = 8080)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sources = (Get-ChildItem -Recurse "$root\src" -Filter *.java).FullName

if (Test-Path "$root\out") { Remove-Item -Recurse -Force "$root\out" }
New-Item -ItemType Directory -Path "$root\out" | Out-Null

& javac -encoding UTF-8 -d "$root\out" $sources
if ($LASTEXITCODE -ne 0) { Write-Error "컴파일 실패"; exit 1 }

Push-Location $root
try {
    & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$root\out" `
        mekicalc.web.WebMain $Port "$root\data" "$root\web"
} finally {
    Pop-Location
}
