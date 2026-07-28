# .exe 패키징 스크립트 (JRE 번들 app-image, Gradle 기반)
#   .\build-exe.ps1            # dist\mekiCalc\mekiCalc.exe 생성
#   .\build-exe.ps1 -Zip       # 추가로 dist\mekiCalc-<ver>-win64.zip 생성
param([string]$Version = "1.0.0", [switch]$Zip)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$stage = "$root\build\jpackage-input"
$dist = "$root\dist"

# 1) 빌드 → desktop\build\install\desktop\lib\*.jar (core.jar + desktop.jar, Main-Class=mekicalc.ui.App)
& "$root\gradlew.bat" :desktop:installDist --console=plain
if ($LASTEXITCODE -ne 0) { Write-Error "빌드 실패"; exit 1 }
$libDir = "$root\desktop\build\install\desktop\lib"

# 2) jpackage 입력 폴더: 런타임 jar 모음(desktop.jar가 메인)
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item "$libDir\*.jar" $stage

# 3) jpackage app-image (JRE 번들, GUI 모드 = 콘솔 없음)
if (Test-Path "$dist\mekiCalc") { Remove-Item -Recurse -Force "$dist\mekiCalc" }
New-Item -ItemType Directory -Path $dist -Force | Out-Null
# 앱 아이콘(.ico)이 있으면 적용
$iconArgs = @()
$icoPath = "$root\icon\maple.ico"
if (Test-Path $icoPath) { $iconArgs = @('--icon', $icoPath) }

& jpackage `
    --type app-image `
    --name mekiCalc `
    --input $stage `
    --main-jar desktop.jar `
    --main-class mekicalc.ui.App `
    --app-version 1.0.0 `
    @iconArgs `
    --dest $dist
if ($LASTEXITCODE -ne 0) { Write-Error "jpackage 실패"; exit 1 }

# 4) data / icon 폴더를 exe 옆에 동봉 (앱이 실행 위치 기준으로 찾음)
Copy-Item -Recurse -Force "$root\data" "$dist\mekiCalc\data"
# 개인 데이터(스탯/큐브 프리셋·환산·범위·UI 설정)는 배포에서 제외 — 새 사용자는 빈 기본값으로 시작.
$personalData = "character.json","presets.json","cube_presets.json","mycube.json","conversions.json","ranges.json","ui.json","oauth.json","sessions.json"
foreach ($pf in $personalData) { Remove-Item "$dist\mekiCalc\data\$pf" -Force -ErrorAction SilentlyContinue }
Remove-Item "$dist\mekiCalc\data\users" -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path "$root\icon") { Copy-Item -Recurse -Force "$root\icon" "$dist\mekiCalc\icon" }
Copy-Item -Force "$root\README.md" "$dist\mekiCalc\README.md"
if (Test-Path "$root\README.txt") { Copy-Item -Force "$root\README.txt" "$dist\mekiCalc\README.txt" }

Write-Host "완료: $dist\mekiCalc\mekiCalc.exe"

# 5) zip
if ($Zip) {
    $zipPath = "$dist\mekiCalc-v$Version-win64.zip"
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path "$dist\mekiCalc\*" -DestinationPath $zipPath
    Write-Host "ZIP: $zipPath"
}
