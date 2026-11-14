param([string]$Tv = "192.168.2.89:5555")
$ErrorActionPreference = 'Stop'
$Package = 'app.orchard.tv.aerialvoice'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'ADB не найден.' }
adb connect $Tv | Out-Host
adb shell pm uninstall --user 0 $Package
Write-Host 'Aerial Voice удалён. Aerial Korobovo и системные службы не изменялись.' -ForegroundColor Green
