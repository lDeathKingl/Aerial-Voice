param([string]$Tv = "192.168.2.89:5555")

$ErrorActionPreference = "Stop"
$Package = "app.orchard.tv.aerialvoice.mictest"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB was not found. Install Android Platform Tools and add adb.exe to PATH."
}

adb connect $Tv | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Could not connect to Android TV through ADB."
}

adb uninstall $Package
if ($LASTEXITCODE -ne 0) {
    throw "Could not uninstall the diagnostic app. Check whether it is installed."
}

Write-Host "Only the Aerial Voice RC In Test package was removed. The main Aerial Voice app was not changed." -ForegroundColor Green
