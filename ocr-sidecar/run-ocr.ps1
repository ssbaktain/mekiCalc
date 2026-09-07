# mekiCalc OCR 사이드카 실행 (미니PC 스케줄드 작업/수동 실행 공용)
# 사용: .\run-ocr.ps1   (환경변수로 조정: OCR_PORT/OCR_WORKERS/OCR_CPU_THREADS)
param(
  [int]$Port = 8090,
  [int]$Workers = 2,
  [int]$CpuThreads = 6
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$env:OCR_PORT = "$Port"
$env:OCR_WORKERS = "$Workers"
$env:OCR_CPU_THREADS = "$CpuThreads"
$env:FLAGS_use_mkldnn = "1"
# oneDNN 이슈 회피 위해 enable_mkldnn은 코드에서 True로 전달(앱 내부). 여기선 환경만 세팅.
Write-Host "OCR sidecar: port=$Port workers=$Workers cpu_threads=$CpuThreads"
python app.py
