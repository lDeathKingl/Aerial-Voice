param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$VoiceApk = "",
    [string]$KorobovoApk = "",
    [string]$BootstrapKeyFile = ""
)

$ErrorActionPreference = "Stop"
$VoicePackage = "app.orchard.tv.aerialvoice.mictest06"
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
    $VoiceApk = Join-Path $PSScriptRoot "aerial-voice-mictest06-debug.apk"
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

# Export the old debug app's visible conversation history before installing the new package.
$historyTemp = Join-Path $env:TEMP ("aerial-voice-history-" + [Guid]::NewGuid().ToString("N") + ".json")
$historyReady = $false
$oldPackages = @("app.orchard.tv.aerialvoice.mictest", "app.orchard.tv.aerialvoice")
foreach ($oldPackage in $oldPackages) {
    $packageCheck = Invoke-Adb @("shell", "pm", "path", $oldPackage)
    if (($packageCheck.Output -join "`n") -notmatch "package:") { continue }
    $oldPrefs = Invoke-Adb @("exec-out", "run-as", $oldPackage, "cat", "shared_prefs/aerial_voice_settings.xml")
    if ($oldPrefs.ExitCode -ne 0) { continue }
    try {
        [xml]$prefsXml = ($oldPrefs.Output -join [Environment]::NewLine)
        $historyNode = $prefsXml.SelectSingleNode("/map/string[@name='conversation_history_json']")
        if ($null -eq $historyNode -or [string]::IsNullOrWhiteSpace($historyNode.InnerText)) { continue }
        $historyItems = @(ConvertFrom-Json -InputObject $historyNode.InnerText)
        if ($historyItems.Count -gt 30) { $historyItems = @($historyItems | Select-Object -Last 30) }
        $historyJson = ConvertTo-Json -InputObject $historyItems -Depth 8 -Compress
        [System.IO.File]::WriteAllText($historyTemp, $historyJson, ([System.Text.UTF8Encoding]::new($false)))
        $historyReady = $true
        Write-Host "Найдена история в старом приложении ($($historyItems.Count) сообщений). Она будет перенесена." -ForegroundColor Green
        break
    }
    catch {
        Write-Warning "Не удалось разобрать историю старого приложения; старое приложение останется нетронутым."
    }
}
if (-not $historyReady) {
    Write-Host "Старую историю автоматически прочитать не удалось. Старое приложение и его данные останутся на месте." -ForegroundColor Yellow
}

Write-Host "2/6 Устанавливаю Aerial Voice 0.6.0 рядом со старой версией..." -ForegroundColor Cyan
$r = Invoke-Adb @("install", "-r", $VoiceApk)
Show-Output $r
if ($r.ExitCode -ne 0) {
    if (($r.Output -join "`n") -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        throw "Подпись Aerial Voice отличается от установленной. Я НЕ удалял приложение и НЕ трогал его данные. Остановись и пришли скриншот."
    }
    throw "Aerial Voice не установился. Старое приложение и его данные не удалялись."
}

$remoteDir = "/sdcard/Android/data/$VoicePackage/files"
$remoteBootstrapFile = ""
$remoteHistoryFile = ""
$temporaryKeyFile = ""
$keyFileToPush = $BootstrapKeyFile

if ([string]::IsNullOrWhiteSpace($keyFileToPush)) {
    Write-Host ""
    Write-Host "Старый Authorization Key зашифрован Android Keystore и не может быть прочитан новым пакетом." -ForegroundColor Yellow
    $secureKey = Read-Host "Вставь Authorization Key GigaChat для переноса (Enter — пропустить)" -AsSecureString
    if ($secureKey.Length -gt 0) {
        $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        try {
            $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
            $temporaryKeyFile = Join-Path $env:TEMP ("aerial-voice-key-" + [Guid]::NewGuid().ToString("N") + ".txt")
            [System.IO.File]::WriteAllText($temporaryKeyFile, $plainKey, ([System.Text.UTF8Encoding]::new($false)))
            $keyFileToPush = $temporaryKeyFile
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
            $plainKey = $null
        }
    }
}

$null = Invoke-Adb @("shell", "mkdir", "-p", $remoteDir)
if (-not [string]::IsNullOrWhiteSpace($keyFileToPush)) {
    if (-not (Test-Path -LiteralPath $keyFileToPush)) {
        throw "Файл Authorization Key не найден: $keyFileToPush"
    }
    $remoteBootstrapFile = "$remoteDir/aerial_voice_bootstrap_key.txt"
    $r = Invoke-Adb @("push", $keyFileToPush, $remoteBootstrapFile)
    Show-Output $r
    if ($r.ExitCode -ne 0) { throw "Не удалось передать GigaChat key на телевизор. Старое приложение не тронуто." }
}
if ($temporaryKeyFile -and (Test-Path -LiteralPath $temporaryKeyFile)) {
    Remove-Item -LiteralPath $temporaryKeyFile -Force
}
if ($historyReady) {
    $remoteHistoryFile = "$remoteDir/aerial_voice_bootstrap_history.json"
    $r = Invoke-Adb @("push", $historyTemp, $remoteHistoryFile)
    Show-Output $r
    if ($r.ExitCode -ne 0) { Write-Warning "Не удалось передать историю на телевизор; старое приложение и история в нём не удалены." }
}
if (Test-Path -LiteralPath $historyTemp) { Remove-Item -LiteralPath $historyTemp -Force }

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

if (-not [string]::IsNullOrWhiteSpace($remoteBootstrapFile)) {
    Write-Host "Проверяю импорт GigaChat key..." -ForegroundColor Cyan
    $imported = $false
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 1
        $check = Invoke-Adb @("shell", "test", "-e", $remoteBootstrapFile)
        if ($check.ExitCode -ne 0) { $imported = $true; break }
    }
    if (-not $imported) {
        Write-Warning "Приложение пока не удалило временный файл ключа. Не запускай установщик повторно; пришли скриншот Aerial Voice."
    } else {
        Write-Host "GigaChat key импортирован и зашифрован Android Keystore." -ForegroundColor Green
    }
}
if (-not [string]::IsNullOrWhiteSpace($remoteHistoryFile)) {
    Write-Host "Проверяю импорт истории..." -ForegroundColor Cyan
    $historyImported = $false
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 1
        $check = Invoke-Adb @("shell", "test", "-e", $remoteHistoryFile)
        if ($check.ExitCode -ne 0) { $historyImported = $true; break }
    }
    if ($historyImported) {
        Write-Host "История импортирована в Aerial Voice 0.6.0." -ForegroundColor Green
    } else {
        Write-Warning "Временный файл истории ещё не удалён. Проверь экран «История» после запуска."
    }
}

Write-Host "6/6 Проверяю установленные версии..." -ForegroundColor Cyan
$r = Invoke-Adb @("shell", "dumpsys", "package", $VoicePackage)
$r.Output | Where-Object { $_ -match "versionName|versionCode" } | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host "Aerial Voice 0.6.0 установлен рядом со старым приложением. Старые данные и старый GigaChat key не удалялись." -ForegroundColor Green
if ([string]::IsNullOrWhiteSpace($remoteBootstrapFile)) {
    Write-Warning "Новый пакет не получил Authorization Key. Добавь его в «Настройки GigaChat»; старый ключ остаётся в старом приложении."
}
if ($bridgeInstalled) {
    Write-Host "Korobovo с F5 bridge установлен. Bridge перехватывает только F5 и только когда HomeGuard сам фильтрует клавиши." -ForegroundColor Green
} else {
    Write-Host "Aerial Voice обновлён; F5 bridge пока не обновлён. Остальные функции помощника доступны." -ForegroundColor Yellow
}
Write-Host "В приложении включи «Диалог» и при желании «Автозапуск»." -ForegroundColor Cyan
Write-Host "Не удаляй старый Aerial Korobovo и не отключай его защиту мыши/геймпада." -ForegroundColor Yellow
