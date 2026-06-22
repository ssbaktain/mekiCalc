# named tunnel을 부팅 환경에서도 안정적으로 실행(작업 스케줄러 자동 시작용).
#  - 부팅 직후 DNS가 준비될 때까지 대기(너무 일찍 떠서 DNS 타임아웃으로 종료되는 문제 방지)
#  - cloudflared가 종료되면 자동 재시작(무한 루프) → 작업이 계속 '실행 중' 유지
# 사전(1회): cloudflared login → tunnel create mekicalc → route dns → ~/.cloudflared/config.yml
$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir "named-tunnel.log"
function Log($m) { ("{0}  {1}" -f (Get-Date -Format "MM-dd HH:mm:ss"), $m) | Add-Content -Path $log }

# cloudflared 실행 파일 경로(PATH → winget/기본 설치 위치 폴백).
$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    foreach ($p in @(
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe",
            "$env:ProgramFiles\cloudflared\cloudflared.exe",
            "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe")) {
        if (Test-Path $p) { $cf = $p; break }
    }
}
if (-not $cf) { Log "cloudflared 실행 파일 없음"; exit 1 }

# 1) 네트워크/DNS 준비 대기 (cloudflared가 쓰는 argotunnel.com SRV가 해석될 때까지, 최대 5분)
Log "네트워크/DNS 대기 시작"
for ($i = 0; $i -lt 60; $i++) {
    try { Resolve-DnsName -Name "_v2-origintunneld._tcp.argotunnel.com" -Type SRV -ErrorAction Stop | Out-Null; Log "DNS 준비됨"; break }
    catch { Start-Sleep -Seconds 5 }
}

# 2) cloudflared 실행 루프 — 종료되면 잠시 후 재시작(부팅 타이밍/일시적 단절 대응).
while ($true) {
    Log "cloudflared 시작 (tunnel run mekicalc)"
    & $cf tunnel run mekicalc 2>&1 | Add-Content -Path $log
    Log "cloudflared 종료 → 15초 후 재시작"
    Start-Sleep -Seconds 15
}
