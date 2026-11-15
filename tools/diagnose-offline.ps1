param(
  [string]$Tv = "192.168.2.89:5555",
  [string]$Package = "app.orchard.tv.aerialvoice.mictest06"
)
$ErrorActionPreference = "Stop"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  throw "ADB не найден. Добавь Android Platform Tools в PATH."
}
& adb connect $Tv | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Не удалось подключиться к ТВ: $Tv" }
$report = New-Object System.Collections.Generic.List[string]
$report.Add("AERIAL VOICE 0.6.3 DIAGNOSTICS")
$report.Add("Date: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
$report.Add("TV: " + $Tv)
$report.Add("")
$report.Add("PACKAGES:")
foreach ($name in @($Package, "app.orchard.tv.aerialvoice.mictest", "app.orchard.tv.korobovo")) {
  $dump = @(& adb -s $Tv shell dumpsys package $name 2>$null)
  $report.Add($name + " -> " + ((@($dump | Select-String -Pattern 'versionName=|versionCode=' | Select-Object -First 2) | ForEach-Object { $_.ToString().Trim() }) -join "; "))
}
$report.Add("")
$report.Add("STORAGE:")
$report.Add((@(& adb -s $Tv shell df -k /data 2>$null) | Select-Object -Last 2) -join [Environment]::NewLine)
$report.Add("")
$report.Add("MODEL FILES:")
$remote = "/sdcard/Android/data/$Package/files/offline-models"
foreach ($path in @("gigaam-v3/model.int8.onnx", "gigaam-v3/tokens.txt", "vosk/vosk-model-small-ru-0.22.zip")) {
  $result = @(& adb -s $Tv shell ls -l "$remote/$path" 2>$null)
  $report.Add($path + ": " + ($result -join " "))
}
$report.Add("")
$report.Add("AUDIO ROUTING / RECOGNITION LOGS (last 200 matching lines):")
$log = @(& adb -s $Tv logcat -d -v brief -s AerialVoiceSpeech:V AerialVoiceVosk:V GigaAM:V '*:S' 2>$null)
$log = @($log | Select-Object -Last 200 | ForEach-Object {
  $line = $_.ToString()
  $line = $line -replace '(?i)(bearer|authorization|api[_-]?key)[\s:=]+\S+', '$1=[REDACTED]'
  $line -replace '[A-Za-z0-9_+\/=\-]{85,}', '[REDACTED]'
})
foreach ($line in $log) { $report.Add($line) }
$out = Join-Path $PSScriptRoot ("aerial-diagnostic-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
[IO.File]::WriteAllLines($out, $report, [Text.UTF8Encoding]::new($false))
Write-Host "Отчёт диагностики сохранён: $out" -ForegroundColor Green
Write-Warning "Перед отправкой отчёта проверь, нет ли в нём личных данных."
