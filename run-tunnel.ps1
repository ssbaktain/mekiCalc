# cloudflared 임시 터널을 포그라운드로 실행(작업 스케줄러 자동 시작용).
# 현재 공개 URL은 logs\cloudflared.log 에 기록된다(재시작 때마다 바뀜).
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# cloudflared 실행 파일 경로 해석(PATH → winget/기본 설치 위치 폴백).
$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    foreach ($p in @(
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe",
            "$env:ProgramFiles\cloudflared\cloudflared.exe",
            "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe")) {
        if (Test-Path $p) { $cf = $p; break }
    }
}
if (-not $cf) { "cloudflared 실행 파일을 찾지 못함" | Out-File "$logDir\cloudflared.log"; exit 1 }

# 포그라운드(-Wait)로 실행 → 작업이 계속 '실행 중' 상태 유지. URL은 표준에러에 출력됨.
Start-Process $cf -ArgumentList 'tunnel', '--url', 'http://localhost:8080' -NoNewWindow -Wait `
    -RedirectStandardError "$logDir\cloudflared.log" -RedirectStandardOutput "$logDir\cloudflared.out.log"
