# 컴파일 후 웹 서버 실행. 사용법:
#   .\build-web.ps1            # 빌드 + http://localhost:8080 실행
#   .\build-web.ps1 -Port 9000 # 다른 포트
param([int]$Port = 8080)

$ErrorActionPreference = "Stop"
# 콘솔을 UTF-8로 맞춤(한글 윈도우 기본 CP949에서 자바 UTF-8 출력이 깨지지 않게).
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
$sources = (Get-ChildItem -Recurse "$root\src" -Filter *.java).FullName

if (Test-Path "$root\out") { Remove-Item -Recurse -Force "$root\out" }
New-Item -ItemType Directory -Path "$root\out" | Out-Null

& javac -encoding UTF-8 -d "$root\out" $sources
if ($LASTEXITCODE -ne 0) { Write-Error "컴파일 실패"; exit 1 }

# 이전에 종료되지 않고 남아 포트를 잡고 있는 웹 서버 java가 있으면 정리 → 재시작 시 포트 충돌 방지(새 코드로 확실히 교체).
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 800

Push-Location $root
try {
    & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$root\out" `
        mekicalc.web.WebMain $Port "$root\data" "$root\web"
} finally {
    Pop-Location
}
