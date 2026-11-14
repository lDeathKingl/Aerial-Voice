param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$VoiceApk = "",
    [string]$KorobovoApk = ""
)

$ErrorActionPreference = "Stop"
$VoicePackage = "app.orchard.tv.aerialvoice.mictest"
$KorobovoPackage = "app.orchard.tv.korobovo"
$MediaListener = "$VoicePackage/app.orchard.tv.aerialvoice.MediaSessionBridge"

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& adb @Arguments 2>&1)
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{
        ExitCode = $code
        Output = @($output | ForEach-Object { $_.ToString() })
    }
}

function Show-Output($result) {
    $result.Output | ForEach-Object { Write-Host $_ }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB не найден. Установи Android Platform Tools и добавь adb.exe в PATH."
}

if ([string]::IsNullOrWhiteSpace($VoiceApk)) {
    $VoiceApk = Join-Path $PSScriptRoot "aerial-voice-mictest-debug.apk"
}
if ([string]::IsNullOrWhiteSpace($KorobovoApk)) {
    $KorobovoApk = Join-Path $PSScriptRoot "aerial-korobovo-voice-bridge.apk"
}
if (-not (Test-Path -LiteralPath $VoiceApk)) {
    throw "Не найден APK Aerial Voice: $VoiceApk"
}

Write-Host "1/6 Подключаюсь к Xiaomi TV: $Tv" -ForegroundColor Cyan
$r = Invoke-Adb @("connect", $Tv)
Show-Output $r
if ($r.ExitCode -ne 0) { throw "Не удалось подключиться к телевизору." }

Write-Host "2/6 Обновляю Aerial Voice 0.6.0..." -ForegroundColor Cyan
$r = Invoke-Adb @("install", "-r", $VoiceApk)
Show-Output $r
if ($r.ExitCode -ne 0) {
    if (($r.Output -join "`n") -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        throw "Подпись Aerial Voice отличается от установленной. Я НЕ удалял приложение и НЕ трогал его данные. Остановись и пришли скриншот."
    }
    throw "Aerial Voice не установился. Старое приложение и его данные не удалялись."
}

Write-Host "3/6 Выдаю необходимые разрешения Aerial Voice..." -ForegroundColor Cyan
$r = Invoke-Adb @("shell", "pm", "grant", $VoicePackage, "android.permission.RECORD_AUDIO")
Show-Output $r
if ($r.ExitCode -ne 0) { Write-Warning "RECORD_AUDIO не выдан автоматически; проверь разрешение микрофона в настройках Android." }

$r = Invoke-Adb @("shell", "appops", "set", $VoicePackage, "GET_USAGE_STATS", "allow")
Show-Output $r
if ($r.ExitCode -ne 0) { Write-Warning "Не удалось выдать доступ к статистике приложений. Его можно включить в кнопке «Контекст ТВ»." }

$r = Invoke-Adb @("shell", "appops", "set", $VoicePackage, "SYSTEM_ALERT_WINDOW", "allow")
Show-Output $r
if ($r.ExitCode -ne 0) { Write-Warning "Не удалось выдать SYSTEM_ALERT_WINDOW. Автозапуск после включения ТВ может не сработать." }

$r = Invoke-Adb @("shell", "cmd", "notification", "allow_listener", $MediaListener)
Show-Output $r
if ($r.ExitCode -ne 0) {
    Write-Warning "Автоматически включить доступ к медиасессиям не удалось. При необходимости открой «Контекст ТВ» → «Разрешение медиасессий»."
}

$bridgeInstalled = $false
if (Test-Path -LiteralPath $KorobovoApk) {
    Write-Host "4/6 Устанавливаю Aerial Korobovo с F5 bridge..." -ForegroundColor Cyan
    $r = Invoke-Adb @("install", "-r", $KorobovoApk)
    Show-Output $r
    if ($r.ExitCode -eq 0) {
        $bridgeInstalled = $true
        $null = Invoke-Adb @("shell", "appops", "set", $KorobovoPackage, "GET_USAGE_STATS", "allow")
    }
    elseif (($r.Output -join "`n") -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        Write-Warning "Подпись Korobovo отличается. Старый Aerial Korobovo оставлен без изменений; F5 bridge не обновлён."
    }
    else {
        Write-Warning "Не удалось обновить Korobovo. Старый лаунчер оставлен без изменений."
    }
}
else {
    Write-Warning "APK Aerial Korobovo не найден. Aerial Voice будет работать вручную, без глобального F5 bridge."
}

Write-Host "5/6 Запускаю Aerial Voice..." -ForegroundColor Cyan
$r = Invoke-Adb @("shell", "monkey", "-p", $VoicePackage, "1")
Show-Output $r
if ($r.ExitCode -ne 0) { Write-Warning "Открой Aerial Voice вручную из списка приложений." }

Write-Host "6/6 Проверяю установленные версии..." -ForegroundColor Cyan
$r = Invoke-Adb @("shell", "dumpsys", "package", $VoicePackage)
$r.Output | Where-Object { $_ -match "versionName|versionCode" } | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host "Aerial Voice 0.6.0 установлен. Существующие данные и сохранённый GigaChat key не удалялись." -ForegroundColor Green
if ($bridgeInstalled) {
    Write-Host "Korobovo с F5 bridge установлен. Bridge перехватывает только F5 и только когда HomeGuard сам фильтрует клавиши." -ForegroundColor Green
} else {
    Write-Host "Aerial Voice обновлён; F5 bridge пока не обновлён. Остальные функции помощника доступны." -ForegroundColor Yellow
}
Write-Host "В приложении включи «Диалог» и при желании «Автозапуск»." -ForegroundColor Cyan
Write-Host "Не удаляй старый Aerial Korobovo и не отключай его защиту мыши/геймпада." -ForegroundColor Yellow
