# mekiCalc 쿠폰 사이드카 실행 (미니PC 스케줄드 작업/수동 실행 공용)
# 사용: .\run-coupon.ps1   (환경변수로 조정: COUPON_PORT/COUPON_HEADLESS)
param(
  [int]$Port = 8091,
  [int]$Headless = 1
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$env:COUPON_PORT = "$Port"
$env:COUPON_HEADLESS = "$Headless"
# 비ASCII 홈(미니PC 한글 사용자명)이면 Playwright 브라우저 경로를 ASCII로(app.py와 동일 기본값). 설치도 이 경로로.
if ($env:USERPROFILE -match '[^\x00-\x7F]') { $env:PLAYWRIGHT_BROWSERS_PATH = 'C:\Users\Public\ms-playwright' }
Write-Host "coupon sidecar: port=$Port headless=$Headless browsers=$($env:PLAYWRIGHT_BROWSERS_PATH)"
python app.py
