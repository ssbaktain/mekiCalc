# 컴파일 후 실행(Gradle 기반). 사용법:
#   .\build.ps1            # GUI 실행
#   .\build.ps1 -SelfTest  # 자체 검증(:desktop:selfTest)
#   .\build.ps1 -Ui        # GUI 실행(기본과 동일)
param([switch]$SelfTest, [switch]$Ui)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if ($SelfTest) {
    & "$root\gradlew.bat" :desktop:selfTest --console=plain
    exit $LASTEXITCODE
}

# GUI: 빌드 후 콘솔 없는 javaw로 분리 실행(터미널 즉시 반환).
& "$root\gradlew.bat" :desktop:installDist --console=plain
if ($LASTEXITCODE -ne 0) { Write-Error "빌드 실패"; exit 1 }
$libGlob = Join-Path $root "desktop\build\install\desktop\lib\*"
Start-Process javaw -ArgumentList @('-Dfile.encoding=UTF-8', '-cp', $libGlob, 'mekicalc.ui.App') -WorkingDirectory $root
