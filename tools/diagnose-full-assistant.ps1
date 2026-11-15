param(
    [string]$Tv = "192.168.2.89:5555",
    [switch]$WithLogs
)

$ErrorActionPreference = "Stop"
$Package = "app.orchard.tv.aerialvoice.mictest06"
$Korobovo = "app.orchard.tv.korobovo"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB не найден. Установи Android Platform Tools."
}

$filename = "aerial-voice-diagnostics-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt"
$outputPath = Join-Path $PSScriptRoot $filename
$lines = New-Object 'System.Collections.Generic.List[string]'

function Add-Result([string]$Title, [string[]]$Arguments) {
    $lines.Add("")
    $lines.Add("### $Title")
    $result = @(& adb @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) {
        $lines.Add("WARNING: ADB exit code $LASTEXITCODE")
    }
    foreach ($value in $result) {
        # Never emit explicit API Authorization headers if present in third-party log lines.
        $safe = $value -replace '(?i)(Authorization\s*[:=]\s*(?:Basic|Bearer)\s+)\S+', '$1[REDACTED]'
        $lines.Add($safe)
    }
}

$lines.Add("Aerial Voice 0.6.1 diagnostics, $(Get-Date -Format s)")
$lines.Add("No app settings or credential files are collected.")
Add-Result "Connect" @("connect", $Tv)
Add-Result "Android version" @("shell", "getprop", "ro.build.version.release")
Add-Result "Aerial Voice package path" @("shell", "pm", "path", $Package)
Add-Result "Aerial Voice version" @("shell", "dumpsys", "package", $Package)
Add-Result "Aerial Voice microphone permission" @("shell", "appops", "get", $Package, "RECORD_AUDIO")
Add-Result "Aerial Voice usage permission" @("shell", "appops", "get", $Package, "GET_USAGE_STATS")
Add-Result "Aerial Voice overlay permission" @("shell", "appops", "get", $Package, "SYSTEM_ALERT_WINDOW")
Add-Result "Aerial Korobovo package path" @("shell", "pm", "path", $Korobovo)

if ($WithLogs) {
    $lines.Add("")
    $lines.Add("IMPORTANT: The following log entries may contain private voice phrases or app names; inspect before sharing.")
    Add-Result "Recent Aerial Voice app logs" @(
        "logcat", "-d", "-t", "300", "-s",
        "AerialVoiceAI:I", "AerialVoiceVosk:I", "AerialVoiceMedia:I",
        "AerialVoiceBoot:I", "AerialVoiceBridge:I", "AndroidRuntime:E", "*:S"
    )
}
[System.IO.File]::WriteAllLines($outputPath, $lines, ([System.Text.UTF8Encoding]::new($false)))
Write-Host "Диагностика сохранена: $outputPath" -ForegroundColor Green
if ($WithLogs) {
    Write-Host "Перед отправкой проверь файл: журналы могут содержать личные данные." -ForegroundColor Yellow
}
