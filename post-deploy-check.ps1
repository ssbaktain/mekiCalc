# 배포 직후 공개 사이트(mekicalc.com) 헬스체크.
#  - 핵심 경로가 전부 200인지 확인. 특히 ads.txt(애드센스)가 깨지면 크게 실패 표시.
#  - 배포 중 원본(미니PC) 재시작 타이밍에 크롤러가 접속하면 5xx → 애드센스가 "ads.txt 찾을 수 없음"으로
#    상태를 뒤집을 수 있어, 재시작이 끝나고 엣지/원본이 안정됐는지 한 번 더 확인하는 용도.
# 사용법:
#   .\post-deploy-check.ps1                 # https://mekicalc.com 점검
#   .\post-deploy-check.ps1 -Base https://www.mekicalc.com
param([string]$Base = "https://mekicalc.com", [int]$Retries = 10, [int]$DelaySec = 3)

$ErrorActionPreference = "Continue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

# (경로, 필수여부, 기대내용 정규식 또는 $null)
$checks = @(
    @{ Path = "/ads.txt";     Critical = $true;  Match = "pub-5018626409439701" },
    @{ Path = "/";            Critical = $true;  Match = $null },
    @{ Path = "/privacy.html";Critical = $false; Match = $null },
    @{ Path = "/api/meta";    Critical = $false; Match = $null }
)

function Test-Url($url, $match) {
    # 캐시 우회용 쿼리 없이 실제 사용자가 받는 그대로 점검. 200 + (옵션)내용 매치면 성공.
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 8
        if ($r.StatusCode -ne 200) { return @{ ok = $false; reason = "HTTP $($r.StatusCode)" } }
        if ($match -and ($r.Content -notmatch $match)) { return @{ ok = $false; reason = "내용 불일치($match 없음)" } }
        return @{ ok = $true; reason = "200" }
    } catch {
        $code = $null
        try { $code = $_.Exception.Response.StatusCode.value__ } catch {}
        return @{ ok = $false; reason = $(if ($code) { "HTTP $code" } else { $_.Exception.Message }) }
    }
}

Write-Host "== 배포 후 헬스체크: $Base ==" -ForegroundColor Cyan
$failCritical = $false
foreach ($c in $checks) {
    $url = "$Base$($c.Path)"
    $res = $null
    for ($i = 1; $i -le $Retries; $i++) {
        $res = Test-Url $url $c.Match
        if ($res.ok) { break }
        if ($i -lt $Retries) { Start-Sleep -Seconds $DelaySec }
    }
    $tag = if ($c.Critical) { "[필수]" } else { "[선택]" }
    if ($res.ok) {
        Write-Host ("  OK   {0,-6} {1,-14} {2}" -f $tag, $c.Path, $res.reason) -ForegroundColor Green
    } else {
        $color = if ($c.Critical) { "Red" } else { "Yellow" }
        Write-Host ("  FAIL {0,-6} {1,-14} {2}" -f $tag, $c.Path, $res.reason) -ForegroundColor $color
        if ($c.Critical) { $failCritical = $true }
    }
}

Write-Host ""
if ($failCritical) {
    Write-Host "*** 배포 검증 실패: 필수 경로가 응답하지 않음. 위 FAIL 항목 확인 필요. ***" -ForegroundColor Red
    Write-Host "    ads.txt가 FAIL이면 애드센스가 '찾을 수 없음'으로 뒤집힐 수 있으니 즉시 원본/터널 상태 확인." -ForegroundColor Red
    exit 1
} else {
    Write-Host "배포 검증 통과: 모든 필수 경로 정상." -ForegroundColor Green
    exit 0
}
