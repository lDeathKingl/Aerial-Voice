param([string]$Tv = "192.168.2.89:5555")
$ErrorActionPreference = 'Stop'
$Package = 'app.orchard.tv.aerialvoice.mictest'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'ADB не найден. Установи Android Platform Tools и добавь adb.exe в PATH.' }
adb connect $Tv | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подключиться к телевизору через ADB.' }
adb uninstall $Package
if ($LASTEXITCODE -ne 0) { throw 'Не удалось удалить диагностическое приложение. Проверь, установлено ли оно.' }
Write-Host 'Удалено только Aerial Voice — RC In Test. Основное Aerial Voice, Aerial Korobovo и Xiaomi VoiceControl не затронуты.' -ForegroundColor Green
