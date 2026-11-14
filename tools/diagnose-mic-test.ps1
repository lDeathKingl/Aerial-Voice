param([string]$Tv = "192.168.2.89:5555")

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB was not found. Install Android Platform Tools and add adb.exe to PATH."
}

adb connect $Tv | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Could not connect to Android TV through ADB."
}

adb logcat -c
Write-Host "Open Aerial Voice RC In Test. Run VOICE_RECOGNITION and MIC tests. Hold the remote microphone button and speak during each test." -ForegroundColor Cyan
Read-Host "After both tests, press Enter here"

Write-Host "=== AERIAL VOICE RC IN TEST LOG ===" -ForegroundColor Green
adb logcat -d -v time | findstr /i "AerialVoiceSpeech SpeechRecognizer RecognitionService AudioRecord"

Write-Host "=== AUDIO INPUT DEVICES ===" -ForegroundColor Green
adb shell dumpsys media.audio_policy | findstr /i "RC In Built-In Mic AUDIO_DEVICE_IN_WIRED_HEADSET AUDIO_DEVICE_IN_BUILTIN_MIC"

Write-Host "=== AUDIO RECORDING ===" -ForegroundColor Green
adb shell dumpsys audio | findstr /i "RecordingActivityMonitor recording client input"

Write-Host "=== DIAGNOSTIC PACKAGE ===" -ForegroundColor Green
adb shell dumpsys package app.orchard.tv.aerialvoice.mictest | findstr /i "versionName versionCode"
