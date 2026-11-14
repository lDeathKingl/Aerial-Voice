param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$Apk = ""
)
$ErrorActionPreference = 'Stop'
$Package = 'app.orchard.tv.aerialvoice.mictest'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'ADB не найден. Установи Android Platform Tools и добавь adb.exe в PATH.' }
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $candidates = @(
        (Join-Path $PSScriptRoot '..\aerial-voice\app\build\outputs\apk\micTest\debug\app-micTest-debug.apk'),
        (Join-Path $PSScriptRoot 'aerial-voice-mic-test-debug.apk'),
        (Join-Path (Get-Location) 'aerial-voice-mic-test-debug.apk')
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Apk -or -not (Test-Path $Apk)) { throw 'Не найден aerial-voice-mic-test-debug.apk. Укажи путь параметром -Apk.' }
Write-Host "Подключение к телевизору $Tv ..." -ForegroundColor Cyan
adb connect $Tv
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подключиться к телевизору через ADB.' }
Write-Host 'Установка Aerial Voice — RC In Test...' -ForegroundColor Cyan
adb install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'Установка не прошла. Если тестовое приложение уже установлено с другой подписью, удали только пакет app.orchard.tv.aerialvoice.mictest и повтори установку. Основное Aerial Voice удалять не нужно.' }
Write-Host 'Выдача разрешения на микрофон...' -ForegroundColor Cyan
adb shell pm grant $Package android.permission.RECORD_AUDIO
if ($LASTEXITCODE -ne 0) { Write-Warning 'Не удалось автоматически выдать RECORD_AUDIO. Разреши микрофон на телевизоре.' }
Write-Host 'Запуск Aerial Voice — RC In Test...' -ForegroundColor Cyan
adb shell am start -n "$Package/.MainActivity"
Start-Sleep -Seconds 2
Write-Host "Проверка установки:" -ForegroundColor Green
adb shell dumpsys package $Package | findstr /i 'versionName versionCode'
Write-Host "Готово. Установлено отдельное диагностическое приложение. Основное Aerial Voice, Aerial Korobovo, Xiaomi VoiceControl и Accessibility-службы не изменялись." -ForegroundColor Green
