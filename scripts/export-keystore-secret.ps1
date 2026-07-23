# Prints base64 of app/debug.keystore for GitHub Actions secret RELEASE_KEYSTORE_BASE64.
# Usage:
#   .\scripts\export-keystore-secret.ps1
# Then: GitHub repo → Settings → Secrets and variables → Actions → New repository secret
#   Name: RELEASE_KEYSTORE_BASE64
#   Value: (paste clipboard / output)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Keystore = Join-Path $ProjectRoot "app\debug.keystore"

if (-not (Test-Path $Keystore)) {
  throw "Keystore not found: $Keystore"
}

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore))
Set-Clipboard -Value $b64
Write-Host "RELEASE_KEYSTORE_BASE64 copied to clipboard ($((Get-Item $Keystore).Length) bytes)."
Write-Host "Add it as a repository secret named RELEASE_KEYSTORE_BASE64."
