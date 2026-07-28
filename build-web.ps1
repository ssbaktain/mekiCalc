# 컴파일 후 웹 서버 실행(Gradle 기반). 사용법:
#   .\build-web.ps1            # 빌드 + http://localhost:8080 실행
#   .\build-web.ps1 -Port 9000 # 다른 포트
param([int]$Port = 8080)

$ErrorActionPreference = "Stop"
# 콘솔을 UTF-8로 맞춤(한글 윈도우 기본 CP949에서 자바 UTF-8 출력이 깨지지 않게).
try { chcp 65001 > $null } catch {}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot

# web 모듈 빌드 → web\build\install\web\lib\*.jar (Spring + core.jar + web.jar + postgresql.jar)
# gradlew는 데몬 시작 안내 등을 stderr로 낼 수 있는데, PS 5.1은 $ErrorActionPreference=Stop에서 그걸
# NativeCommandError로 종료 처리해 스크립트가 빌드 뒤 java 실행 전에 죽는다(스케줄드태스크의 새 세션에서
# 데몬이 처음 뜰 때 특히). → 이 구간만 Continue로 두고 $LASTEXITCODE로만 성공 판정. --no-daemon으로 잔여 데몬도 방지.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& "$root\gradlew.bat" :web:installDist --console=plain --no-daemon
$buildCode = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($buildCode -ne 0) { Write-Error "빌드 실패"; exit 1 }

# 이전에 종료되지 않고 남아 포트를 잡고 있는 웹 서버 java가 있으면 정리 → 재시작 시 포트 충돌 방지(새 코드로 확실히 교체).
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 800

# 서버 콘솔 출력(시작 로그·예외 등)을 날짜별 롤링 파일로도 남김(미니PC 스케줄드태스크는 콘솔이 없어
# 이게 없으면 출력이 소실됨). logs/ 는 .gitignore 처리되어 로컬 보관.
$logDir = Join-Path $root "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$logFile = Join-Path $logDir ("server-{0}.log" -f (Get-Date -Format "yyyyMMdd"))

$libGlob = Join-Path $root "web\build\install\web\lib\*"
Push-Location $root
try {
    # 2>&1 | Tee = 콘솔+파일 동시 기록. PS 5.1은 네이티브 stderr 병합 시 NativeCommandError로 파이프라인을
    # 끊을 수 있어, 서버가 죽지 않도록 이 구간만 ErrorActionPreference를 Continue로 둔다.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    # Spring Boot(WebApplication) 실행. 데이터/정적 폴더·포트는 프로퍼티로 전달.
    & java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $libGlob `
        mekicalc.web.boot.WebApplication `
        "--server.port=$Port" "--mekicalc.data-dir=$root\data" "--mekicalc.web-dir=$root\public" 2>&1 |
        Tee-Object -FilePath $logFile -Append
    $ErrorActionPreference = $prevEAP
} finally {
    Pop-Location
}
