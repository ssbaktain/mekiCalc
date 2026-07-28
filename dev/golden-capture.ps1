# 골든 마스터 캡처: 전 엔드포인트 응답 바디 + 핵심 헤더를 파일로 저장.
# Spring Boot 마이그레이션 전후로 실행해 바이트 단위 비교하는 안전망.
# 사용법: .\dev\golden-capture.ps1 -BaseUrl http://localhost:8899 -OutDir .\dev\golden\before
param(
    [string]$BaseUrl = "http://localhost:8899",
    [string]$OutDir  = ".\dev\golden\before"
)
$ErrorActionPreference = "Stop"
if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

function Save($name, $method, $path, $bodyObj) {
    $url = "$BaseUrl$path"
    $headersOut = ""
    try {
        if ($method -eq "GET") {
            $resp = Invoke-WebRequest -Uri $url -Method GET -UseBasicParsing
        } else {
            $json = if ($bodyObj -ne $null) { $bodyObj | ConvertTo-Json -Depth 20 -Compress } else { "{}" }
            $resp = Invoke-WebRequest -Uri $url -Method POST -UseBasicParsing -ContentType "application/json" -Body $json
        }
        $status = $resp.StatusCode
        $ct = $resp.Headers["Content-Type"]
        $cc = $resp.Headers["Cache-Control"]
        # 바디는 그대로(바이트 비교용). 응답이 JSON이면 그대로 저장.
        $noBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText((Join-Path $OutDir "$name.body"), $resp.Content, $noBom)
        $headersOut = "$name`t$status`tCT=$ct`tCC=$cc"
    } catch {
        $code = try { [int]$_.Exception.Response.StatusCode } catch { -1 }
        $headersOut = "$name`tERROR($code)`t$($_.Exception.Message -replace '\s+',' ')"
    }
    Add-Content -Path (Join-Path $OutDir "_headers.txt") -Value $headersOut
    Write-Host $headersOut
}

# --- 실제 스탯 확보(POST 페이로드용) ---
$my = Invoke-RestMethod -Uri "$BaseUrl/api/mydata" -Method GET
$stats = $my.stats
$conv  = if ($my.conversions) { $my.conversions } else { @{} }
$cube  = if ($my.cubeState) { $my.cubeState } else { @{} }
# 공통 계산 컨텍스트
$ctx = @{ stats = $stats; conversions = $conv; buffs = @(); hitInterval = 0; chapter = "31"; target = "5" }

# --- GET 엔드포인트 ---
Save "get_stats"            GET  "/api/stats"             $null
Save "get_meta"             GET  "/api/meta"              $null
Save "get_mydata"           GET  "/api/mydata"            $null
Save "get_gear-options"     GET  "/api/gear-options"      $null
Save "get_bootstrap"        GET  "/api/bootstrap"         $null
Save "get_me"               GET  "/api/me"                $null
Save "get_notice-categories" GET "/api/notice-categories" $null
Save "get_notices"          GET  "/api/notices"           $null
Save "get_coupons"          GET  "/api/coupons"           $null
Save "get_bot-health"       GET  "/api/bot/outbox/health" $null

# --- POST 계산 엔드포인트 ---
Save "post_damage"       POST "/api/damage"       ($ctx + @{ companionId = ""; grade = ""; level = 0 })
Save "post_qualitative"  POST "/api/qualitative"  @{ job = $stats.JOB; conversions = $conv }
Save "post_buffs"        POST "/api/buffs"        @{ stats = $stats }
Save "post_target-damage" POST "/api/target-damage" ($ctx + @{ pen = 0 })
Save "post_companion-dps" POST "/api/companion-dps" ($ctx + @{ companionId = ""; grade = ""; level = 0; ratio = 0 })
Save "post_slotdraws"    POST "/api/slotdraws"    @{ part = "무기"; grade = "LEGENDARY" }
Save "post_evaluate"     POST "/api/evaluate"     ($ctx + @{ part = "무기"; grade = "LEGENDARY" })

# --- 정적 파일(헤더 특히 중요: 캐시/버전스탬프) ---
Save "static_index"      GET  "/"                 $null
Save "static_appjs"      GET  "/app.js"           $null
Save "static_style"      GET  "/style.css"        $null

Write-Host ""
Write-Host "완료 → $OutDir"
