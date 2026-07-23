# One-command STANDALONE APK build for MeloManiac (native Kotlin).

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $ProjectRoot "gradlew.bat"))) {
  $ProjectRoot = (Get-Location).Path
}

$SdkDir = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$GradleHome = "C:\g"
$OutDir = Join-Path $ProjectRoot "dist"
$ApkSrc = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
$ApkDst = Join-Path $OutDir "MeloManiac.apk"

Write-Host "==> MeloManiac Kotlin STANDALONE APK" -ForegroundColor Cyan

if (-not (Test-Path $SdkDir)) {
  throw "Android SDK not found at $SdkDir"
}

New-Item -ItemType Directory -Force -Path $GradleHome | Out-Null
$env:GRADLE_USER_HOME = $GradleHome
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

$sdkDirForward = $SdkDir -replace '\\', '/'
@(
  '## Machine-local SDK path. Do not commit.',
  "sdk.dir=$sdkDirForward"
) | Set-Content -Path (Join-Path $ProjectRoot "local.properties") -Encoding ASCII

Write-Host "==> Building arm64-v8a RELEASE APK..."

Push-Location $ProjectRoot
try {
  & .\gradlew.bat assembleRelease --no-daemon -PreactNativeArchitectures=arm64-v8a
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}

if (-not (Test-Path $ApkSrc)) {
  throw "APK not found at $ApkSrc"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Copy-Item -Force $ApkSrc $ApkDst

Write-Host ""
Write-Host "BUILD OK - standalone Kotlin app" -ForegroundColor Green
Write-Host "APK: $ApkDst"
Write-Host "  adb uninstall com.melomaniac.app"
Write-Host "  adb install -r `"$ApkDst`""
