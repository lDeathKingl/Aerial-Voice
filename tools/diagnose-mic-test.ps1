param([string]$Tv = "192.168.2.89:5555")
$ErrorActionPreference = 'Stop'
adb connect $Tv | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подключиться к телевизору через ADB.' }
adb logcat -c
Write-Host "Открой Aerial Voice — RC In Test. Нажми «Проверить микрофон пульта · RC In», затем по очереди проверь VOICE_RECOGNITION и MIC. Во время теста удерживай кнопку микрофона на пульте и говори." -ForegroundColor Cyan
Read-Host 'После двух тестов нажми Enter здесь'
Write-Host '=== AERIAL VOICE RC IN TEST LOG ===' -ForegroundColor Green
adb logcat -d -v time | findstr /i "AerialVoiceSpeech SpeechRecognizer RecognitionService AudioRecord"
Write-Host '=== AUDIO INPUT DEVICES ===' -ForegroundColor Green
adb shell dumpsys media.audio_policy | findstr /i "RC In Built-In Mic AUDIO_DEVICE_IN_WIRED_HEADSET AUDIO_DEVICE_IN_BUILTIN_MIC"
Write-Host '=== AUDIO RECORDING ===' -ForegroundColor Green
adb shell dumpsys audio | findstr /i "RecordingActivityMonitor recording client input"
Write-Host '=== DIAGNOSTIC PACKAGE ===' -ForegroundColor Green
adb shell dumpsys package app.orchard.tv.aerialvoice.mictest | findstr /i "versionName versionCode"
