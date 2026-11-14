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
        (Join-Path $PSScriptRoot "aerial-voice-vosk-test-debug.apk"),
        (Join-Path (Get-Location) "aerial-voice-vosk-test-debug.apk")
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Apk -or -not (Test-Path $Apk)) {
    throw "APK not found: aerial-voice-vosk-test-debug.apk. Pass its path using -Apk."
}

Write-Host "Connecting to Android TV at $Tv ..." -ForegroundColor Cyan
adb connect $Tv
if ($LASTEXITCODE -ne 0) {
    throw "Could not connect to Android TV through ADB."
}

Write-Host "Installing Aerial Voice Vosk Test..." -ForegroundColor Cyan
$installOutput = & adb install -r $Apk 2>&1 | Out-String
$installExitCode = $LASTEXITCODE
Write-Host $installOutput

if ($installExitCode -ne 0 -and $installOutput -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Host "Android found an older diagnostic app signed with a different key." -ForegroundColor Yellow
    Write-Host "Only the separate test package will be removed: $Package" -ForegroundColor Yellow
    Write-Host "Its local app data will be erased. The main Aerial Voice app will NOT be touched." -ForegroundColor Yellow
    $confirmation = Read-Host "Remove the old test app and install Vosk Test? (Y/N)"
    if ($confirmation -notmatch '^(?i:y|yes|д|да)
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
) {
        throw "Cancelled. No app was uninstalled."
    }

    Write-Host "Removing only the old diagnostic package..." -ForegroundColor Cyan
    & adb uninstall $Package
    if ($LASTEXITCODE -ne 0) {
        throw "Could not uninstall the old diagnostic package. The main Aerial Voice app was not changed."
    }

    Write-Host "Installing Vosk Test after removing the old test copy..." -ForegroundColor Cyan
    & adb install $Apk
    if ($LASTEXITCODE -ne 0) {
        throw "Installation failed after removing the diagnostic package. The main Aerial Voice app was not changed."
    }
} elseif ($installExitCode -ne 0) {
    throw "Installation failed for a reason other than a signing conflict. Check the ADB output above."
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
