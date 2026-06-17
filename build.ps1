# 컴파일 후 실행. 사용법:
#   .\build.ps1            # CLI 데모 실행
#   .\build.ps1 -SelfTest  # 자체 검증
#   .\build.ps1 -Ui        # GUI 실행
param([switch]$SelfTest, [switch]$Ui)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sources = (Get-ChildItem -Recurse "$root\src" -Filter *.java).FullName

if (Test-Path "$root\out") { Remove-Item -Recurse -Force "$root\out" }
New-Item -ItemType Directory -Path "$root\out" | Out-Null

& javac -encoding UTF-8 -d "$root\out" $sources
if ($LASTEXITCODE -ne 0) { Write-Error "컴파일 실패"; exit 1 }

Push-Location $root
try {
    $mainClass = if ($Ui) { 'mekicalc.ui.App' } else { 'mekicalc.Main' }
    $javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', "$root\out", $mainClass)
    if ($SelfTest) { $javaArgs += '--selftest' }
    if ($Ui) {
        # GUI는 콘솔 없는 javaw로 띄워 cmd 창이 깜빡이지 않게 한다(터미널도 바로 반환).
        Start-Process javaw -ArgumentList $javaArgs -WorkingDirectory $root
    } else {
        & java @javaArgs
    }
} finally {
    Pop-Location
}
