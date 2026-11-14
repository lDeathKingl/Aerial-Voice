param([string]$Tv = "192.168.2.89:5555")
$ErrorActionPreference = 'Stop'
adb connect $Tv | Out-Host
adb logcat -c
Write-Host "Открой Aerial Voice на телевизоре, нажми «Начать разговор» и произнеси короткую фразу по-русски." -ForegroundColor Cyan
Read-Host 'Когда распознавание завершится или покажет ошибку, нажми Enter здесь'
Write-Host "`n=== AERIAL VOICE LOG ===" -ForegroundColor Green
adb logcat -d -v time | findstr /i "AerialVoice SpeechRecognizer TextToSpeech RecognitionService"
Write-Host "`n=== RECOGNITION SERVICE ===" -ForegroundColor Green
adb shell settings get secure voice_recognition_service
Write-Host "`n=== AUDIO INPUT DEVICES ===" -ForegroundColor Green
adb shell dumpsys media.audio_policy | findstr /i "input device bluetooth sco headset microphone"
Write-Host "`n=== AUDIO RECORDING ===" -ForegroundColor Green
adb shell dumpsys audio | findstr /i "recording active input client"
Write-Host "`n=== AERIAL VOICE PACKAGE ===" -ForegroundColor Green
adb shell dumpsys package app.orchard.tv.aerialvoice | findstr /i "versionName versionCode"
Write-Host "`nЕсли позже применим HomeGuard bridge-патч, этот же лог покажет события AerialVoiceBridge." -ForegroundColor Cyan
