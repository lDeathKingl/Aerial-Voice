# Read-only GigaChat key import diagnostics for Windows PowerShell 5.1.
# Does not print the key or its encrypted value.
param(
  [string]$Tv = "192.168.2.89:5555",
  [string]$Package = "app.orchard.tv.aerialvoice.mictest06"
)
$ErrorActionPreference = "Continue"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  throw "adb.exe is missing from PATH."
}
Write-Host ("TV: " + $Tv) -ForegroundColor Cyan
$state = @(& adb -s $Tv get-state 2>$null)
if ($LASTEXITCODE -ne 0 -or ($state -join "").Trim() -ne "device") {
  throw "ADB connection failed. Check TV IP and authorization."
}
$installed = @(& adb -s $Tv shell pm path $Package 2>$null)
if ($LASTEXITCODE -ne 0 -or ($installed -join "") -notmatch "package:") {
  throw "Aerial Voice 0.6.4 package is not installed."
}

Write-Host "Starting the main Aerial Voice activity explicitly..." -ForegroundColor Cyan
& adb -s $Tv shell am start -W -n "$Package/app.orchard.tv.aerialvoice.MainActivity"
if ($LASTEXITCODE -ne 0) {
  Write-Warning "Could not start the Aerial Voice activity."
}

Start-Sleep -Seconds 2
$pidResult = @(& adb -s $Tv shell pidof $Package 2>$null)
if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($pidResult -join "").Trim())) {
  Write-Host "APP_PROCESS: RUNNING" -ForegroundColor Green
} else {
  Write-Host "APP_PROCESS: NOT RUNNING" -ForegroundColor Red
}

$remoteStatus = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.status"
$ack = @(& adb -s $Tv shell cat $remoteStatus 2>$null)
if ($LASTEXITCODE -eq 0) {
  Write-Host ("IMPORT_ACK: " + ($ack -join "").Trim()) -ForegroundColor Green
} else {
  Write-Host "IMPORT_ACK: not present (older installer may have deleted it)."
}

# Read only presence of the encrypted SharedPreferences entry. Never print ciphertext.
& adb -s $Tv shell run-as $Package grep -q api_key_ciphertext shared_prefs/aerial_voice_settings.xml 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
  Write-Host "ENCRYPTED_GIGACHAT_KEY: PRESENT" -ForegroundColor Green
  Write-Host "This confirms stored encrypted data, NOT that GigaChat auth succeeds." -ForegroundColor Yellow
} else {
  Write-Host "ENCRYPTED_GIGACHAT_KEY: not verified (missing or run-as unavailable)." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Recent app startup errors (avoid sharing unrelated/private logs):" -ForegroundColor Cyan
$lines = @(& adb -s $Tv logcat -d -v brief -s AndroidRuntime:E AerialVoice:W AerialVoiceSpeech:E '*:S' 2>$null)
$filtered = @($lines | Select-Object -Last 40)
if ($filtered.Count -eq 0) {
  Write-Host "(none found)"
} else {
  foreach ($line in $filtered) {
    $clean = $line -replace '(?i)(authorization|bearer|api[_-]?key)[ ]*[:=][ ]*[^ ]+', '$1=[REDACTED]'
    $clean = $clean -replace '[a-zA-Z0-9+/=_-]{85,}', '[REDACTED]'
    Write-Host $clean
  }
}
Write-Host ""
Write-Host "If ENCRYPTED_GIGACHAT_KEY is PRESENT, first test GigaChat in the TV app. Do not re-enter the key yet."
