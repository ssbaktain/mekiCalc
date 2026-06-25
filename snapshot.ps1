# 하루 1회 유저 프리셋 통계 스냅샷 덤프(미니PC 스케줄드 작업 'MekiCalc-Snapshot'에서 호출).
# DB 전체 유저 번들을 떠서 logs/snapshots/YYYY-MM-DD.jsonl.gz 로 dedup 저장(1년 보관).
# out/ 클래스는 웹 서버 태스크(build-web.ps1)가 시작 시 컴파일해 유지하므로 여기선 재컴파일하지 않는다.
$ErrorActionPreference = "Continue"
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
$logDir = Join-Path $root "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir "snapshot.log"
("[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "] snapshot 시작") | Out-File -FilePath $log -Append -Encoding utf8

if (-not (Test-Path "$root\out")) {
    "out\ 없음 — 웹 서버 태스크가 먼저 시작/컴파일되어야 함. 건너뜀." | Out-File -FilePath $log -Append -Encoding utf8
    exit 1
}

& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$root\out;$root\lib\*" `
    mekicalc.web.WebMain --snapshot "$root\data" 2>&1 |
    Tee-Object -FilePath $log -Append
