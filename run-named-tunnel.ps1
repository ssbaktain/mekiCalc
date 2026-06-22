# 고정 주소 named tunnel 실행(작업 스케줄러 자동 시작용).
# 사전 준비(1회): cloudflared tunnel login → tunnel create mekicalc → route dns → ~/.cloudflared/config.yml 작성.
# config.yml의 ingress(service: http://localhost:8080)와 credentials-file을 사용한다.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    foreach ($p in @(
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe",
            "$env:ProgramFiles\cloudflared\cloudflared.exe",
            "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe")) {
        if (Test-Path $p) { $cf = $p; break }
    }
}
if (-not $cf) { "cloudflared 실행 파일을 찾지 못함" | Out-File "$logDir\named-tunnel.log"; exit 1 }

# 포그라운드(-Wait)로 named tunnel 실행 → 작업이 계속 '실행 중' 유지.
Start-Process $cf -ArgumentList 'tunnel', 'run', 'mekicalc' -NoNewWindow -Wait `
    -RedirectStandardError "$logDir\named-tunnel.log" -RedirectStandardOutput "$logDir\named-tunnel.out.log"
