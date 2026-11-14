param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"
$Package = "app.orchard.tv.aerialvoice.mictest"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB was not found. Install Android Platform Tools and add adb.exe to PATH."
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $candidates = @(
        (Join-Path $PSScriptRoot "..\aerial-voice\app\build\outputs\apk\micTest\debug\app-micTest-debug.apk"),
        (Join-Path $PSScriptRoot "aerial-voice-mic-test-debug.apk"),
        (Join-Path (Get-Location) "aerial-voice-mic-test-debug.apk")
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Apk -or -not (Test-Path $Apk)) {
    throw "APK not found: aerial-voice-mic-test-debug.apk. Pass its path using -Apk."
}

Write-Host "Connecting to Android TV at $Tv ..." -ForegroundColor Cyan
adb connect $Tv
if ($LASTEXITCODE -ne 0) {
    throw "Could not connect to Android TV through ADB."
}

Write-Host "Installing Aerial Voice RC In Test..." -ForegroundColor Cyan
adb install -r $Apk
if ($LASTEXITCODE -ne 0) {
    throw "Installation failed. If a test build with a different signature is installed, uninstall only app.orchard.tv.aerialvoice.mictest and retry. Do not uninstall the main Aerial Voice app."
}

Write-Host "Granting microphone permission..." -ForegroundColor Cyan
adb shell pm grant $Package android.permission.RECORD_AUDIO
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Could not grant RECORD_AUDIO automatically. Allow microphone permission on the TV."
}

Write-Host "Launching diagnostic app..." -ForegroundColor Cyan
adb shell monkey -p $Package 1
Start-Sleep -Seconds 2

Write-Host "Installed package version:" -ForegroundColor Green
adb shell dumpsys package $Package | findstr /i "versionName versionCode"

Write-Host "Done. Only the separate diagnostic package was installed. The main Aerial Voice app and other apps were not changed." -ForegroundColor Green
