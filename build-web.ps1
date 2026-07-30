# 컴파일 후 웹 서버(Spring) 실행 — 스케줄드태스크(MekiCalc-Web)와 수동 실행 공용.
#   .\build-web.ps1            # 빌드 + http://localhost:8080 실행
#   .\build-web.ps1 -Port 9000 # 다른 포트
#   .\build-web.ps1 -Dev       # 로컬 개발: dev 프로파일(관리자 게이팅 우회 등). 프로덕션/미니PC는 절대 사용 금지.
param([int]$Port = 8080, [switch]$Dev)

$ErrorActionPreference = "Stop"
# 콘솔을 UTF-8로 맞춤(한글 윈도우 기본 CP949에서 자바 UTF-8 출력이 깨지지 않게).
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
# ★스케줄드태스크는 작업디렉토리 미지정(CWD=System32)이라, gradlew가 현재 폴더에서 settings.gradle을
#   못 찾아 "Run gradlew init" 오류로 빌드 실패한다. 프로젝트 루트로 이동해야 함(수동 실행은 원래 루트라 무해).
Set-Location $root

$logDir = Join-Path $root "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
# 부팅 단계 마커(스케줄드태스크는 콘솔이 없어 실패 지점을 이 파일로 추적). logs/ 는 gitignore.
$bootLog = Join-Path $logDir "deploy-boot.log"
function Note($m) { ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) | Out-File $bootLog -Append -Encoding utf8 }
Note "build-web start port=$Port JAVA_HOME=$env:JAVA_HOME"

# JAVA_HOME 보정: 스케줄드태스크 세션엔 JAVA_HOME이 없을 수 있어 gradle이 JDK를 못 찾고 빌드가 실패한다
# (SSH/대화형 세션엔 있어서 안 걸림). PATH의 java 위치에서 유도해 채운다.
if (-not $env:JAVA_HOME) {
    $jc = (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($jc) { $env:JAVA_HOME = Split-Path (Split-Path $jc); Note "JAVA_HOME set from PATH: $env:JAVA_HOME" }
    else { Note "WARNING: java not on PATH and JAVA_HOME unset" }
}

# web 모듈 빌드 → web\build\install\web\lib\*.jar. gradle 출력은 파일로 캡처(태스크 hidden 창에서 유실 방지).
# PS 5.1은 $ErrorActionPreference=Stop에서 gradle stderr를 NativeCommandError로 종료 처리하므로 이 구간만 Continue.
$gradleLog = Join-Path $logDir "gradle-build.log"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& "$root\gradlew.bat" :web:installDist --console=plain --no-daemon *> $gradleLog
$buildCode = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
Note "gradle installDist exit=$buildCode"
if ($buildCode -ne 0) { Note "BUILD FAILED (see gradle-build.log)"; Write-Error "빌드 실패"; exit 1 }

# 이전 웹 서버 java 정리(포트 충돌 방지, 새 코드로 확실히 교체).
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 800
Note "old java stopped, launching Spring on $Port"

$logFile = Join-Path $logDir ("server-{0}.log" -f (Get-Date -Format "yyyyMMdd"))
$libGlob = Join-Path $root "web\build\install\web\lib\*"
Push-Location $root
try {
    # 서버 콘솔 출력을 날짜별 롤링 파일로도 남김. 2>&1|Tee 중 네이티브 stderr가 파이프라인을 끊지 않도록 Continue.
    $ErrorActionPreference = "Continue"
    $javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', $libGlob,
        'mekicalc.web.boot.WebApplication',
        "--server.port=$Port", "--mekicalc.data-dir=$root\data", "--mekicalc.web-dir=$root\public")
    # -Dev: 로컬 개발 프로파일(관리자 게이팅 우회). 프로덕션 스케줄드태스크는 -Dev 없이 실행 → default 프로파일.
    if ($Dev) { $javaArgs += '--spring.profiles.active=dev'; Note "DEV PROFILE ON (admin bypass)" }
    & java @javaArgs 2>&1 | Tee-Object -FilePath $logFile -Append
} finally {
    Pop-Location
}
