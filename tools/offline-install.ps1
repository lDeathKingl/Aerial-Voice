# Offline installer: no TV-side network, no app/data removal.
param(
  [string]$Tv = "192.168.2.89:5555",
  [string]$Apk = (Join-Path $PSScriptRoot "aerial-voice-mictest06-debug.apk"),
  [string]$Models = (Join-Path $PSScriptRoot "offline-models"),
  [string]$Package = "app.orchard.tv.aerialvoice.mictest06",
  [switch]$SkipHistory
)
$ErrorActionPreference = "Stop"
function Adb([string[]]$ArgsList) {
  & adb @ArgsList
  if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($ArgsList -join ' ')" }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb.exe not found in PATH" }
if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }
$manifestPath = Join-Path $Models "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing offline-models/manifest.json. Run prepare-offline-models.ps1 on PC first." }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.format -ne "AerialVoice-offline-model-cache-v1") { throw "Unsupported model manifest" }
$expected = @(
  "vosk/vosk-model-small-ru-0.22.zip",
  "gigaam-v3/model.int8.onnx",
  "gigaam-v3/tokens.txt"
)
foreach ($relative in $expected) {
  $entry = @($manifest.files | Where-Object { $_.path -eq $relative }) | Select-Object -First 1
  if (-not $entry) { throw "Not listed in manifest: $relative" }
  $local = Join-Path $Models ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
  if (-not (Test-Path -LiteralPath $local)) { throw "Missing: $local" }
  $file = Get-Item -LiteralPath $local
  if ([long]$file.Length -ne [long]$entry.bytes) { throw "Incorrect size: $relative" }
  $hash = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($hash -ne [string]$entry.sha256) { throw "SHA256 mismatch: $relative" }
  Write-Host "OK: $relative" -ForegroundColor Green
}
Adb -ArgsList @("connect", $Tv)
Write-Host "Подключение к $Tv" -ForegroundColor Cyan
# Report available space where Android stores app models. Do not silently wipe anything.
$dfResult = @(& adb -s $Tv shell df -k /data 2>$null)
if ($LASTEXITCODE -eq 0 -and $dfResult.Count -gt 1) {
  $parts = (($dfResult | Select-Object -Last 1).Trim() -split '\s+')
  if ($parts.Count -ge 4 -and $parts[3] -match '^[0-9]+$') {
    $freeKiB = [long]$parts[3]
    Write-Host ("Свободно в /data: {0} МБ" -f [math]::Floor($freeKiB / 1024))
    if ($freeKiB -lt (400 * 1024)) { throw "Нужно освободить на ТВ не менее 400 МБ памяти." }
  }
}
# adb install -r retains app data and fails safely on a signing mismatch.
Adb -ArgsList @("-s", $Tv, "install", "-r", $Apk)
& adb -s $Tv shell pm grant $Package android.permission.RECORD_AUDIO 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning "Проверь разрешение микрофона на ТВ." }
& adb -s $Tv shell appops set $Package GET_USAGE_STATS allow 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning "Доступ к контексту приложений можно выдать вручную." }
$remote = "/sdcard/Android/data/$Package/files/offline-models"
Adb -ArgsList @("-s", $Tv, "shell", "mkdir", "-p", "$remote/vosk", "$remote/gigaam-v3")
foreach ($relative in $expected) {
  $local = Join-Path $Models ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
  $dest = "$remote/$relative"
  Adb -ArgsList @("-s", $Tv, "push", $local, $dest)
  $entry = @($manifest.files | Where-Object { $_.path -eq $relative }) | Select-Object -First 1
  $remoteHash = @(& adb -s $Tv shell sha256sum $dest 2>$null)
  if ($LASTEXITCODE -ne 0 -or ($remoteHash -join "") -notmatch "^[0-9a-fA-F]{64}") {
    throw "TV has no sha256sum, or hash check failed for $relative. Models were transferred but not verified."
  }
  $digest = (($remoteHash -join "") -split '\s+')[0].ToLowerInvariant()
  if ($digest -ne [string]$entry.sha256) { throw "TV SHA256 mismatch: $relative" }
}
# Best-effort transfer of plain conversation history from 0.5.2 (never its encrypted key).
$remoteHistory = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_history.json"
$historyStaged = $false
if (-not $SkipHistory) {
  $legacyPackage = "app.orchard.tv.aerialvoice.mictest"
  $legacyInstalled = @(& adb -s $Tv shell pm path $legacyPackage 2>$null)
  if ($LASTEXITCODE -eq 0 -and ($legacyInstalled -join "") -match "package:") {
    try {
      $oldPrefs = @(& adb -s $Tv exec-out run-as $legacyPackage cat shared_prefs/aerial_voice_settings.xml 2>$null)
      if ($LASTEXITCODE -ne 0) { throw "run-as старого приложения недоступен" }
      [xml]$parsed = $oldPrefs -join [Environment]::NewLine
      $historyNode = $parsed.SelectSingleNode("/map/string[@name='conversation_history_json']")
      if ($null -ne $historyNode -and -not [string]::IsNullOrWhiteSpace($historyNode.InnerText)) {
        $items = @(ConvertFrom-Json $historyNode.InnerText)
        if ($items.Count -gt 30) { $items = @($items | Select-Object -Last 30) }
        $textToSave = ConvertTo-Json -InputObject $items -Depth 12 -Compress
        $tmpHistory = Join-Path ([IO.Path]::GetTempPath()) ("aerial-history-" + [Guid]::NewGuid().ToString("N") + ".json")
        try {
          [IO.File]::WriteAllText($tmpHistory, $textToSave, [Text.UTF8Encoding]::new($false))
          Adb -ArgsList @("-s", $Tv, "push", $tmpHistory, $remoteHistory)
          $historyStaged = $true
          Write-Host "История 0.5.2 подготовлена к переносу." -ForegroundColor Green
        } finally {
          Remove-Item -LiteralPath $tmpHistory -Force -ErrorAction SilentlyContinue
        }
      }
    } catch {
      Write-Warning ("Историю 0.5.2 перенести не удалось. Старые данные сохранены: " + $_.Exception.Message)
    }
  }
}

# One-time GigaChat key bootstrap. The key is NEVER embedded in APK,
# printed, added to command-line parameters or committed to GitHub.
# The app imports it on launch, stores it in Android Keystore and deletes the file.
$remoteKey = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.txt"
$remoteKeyStatus = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.status"
$remoteKeyStaged = $false
Write-Host ""
Write-Host "GigaChat: введите Authorization Key на компьютере." -ForegroundColor Cyan
Write-Host "Если ключа пока нет, нажмите Enter: распознавание будет работать без него." -ForegroundColor Yellow
$secureKey = Read-Host "Authorization Key (ввод скрыт)" -AsSecureString
if ($null -ne $secureKey -and $secureKey.Length -gt 0) {
  $keyPointer = [IntPtr]::Zero
  $keyText = $null
  $temporaryKeyFile = Join-Path ([IO.Path]::GetTempPath()) ("aerial-voice-bootstrap-" + [Guid]::NewGuid().ToString("N") + ".tmp")
  try {
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $keyText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    if ([string]::IsNullOrWhiteSpace($keyText)) { throw "Authorization Key is empty" }
    if ([Text.Encoding]::UTF8.GetByteCount($keyText.Trim()) -gt 16384) { throw "Authorization Key is too long" }
    # PowerShell 5.1's Set-Content -Encoding UTF8 adds a BOM. Use BOM-free UTF8.
    [IO.File]::WriteAllText($temporaryKeyFile, $keyText.Trim(), [Text.UTF8Encoding]::new($false))
    # Remove any stale success marker before this import attempt.
    & adb -s $Tv shell rm -f $remoteKeyStatus 2>$null | Out-Null
    $remoteKeyStaged = $true
    Adb -ArgsList @("-s", $Tv, "push", $temporaryKeyFile, $remoteKey)
    Write-Host "Authorization Key передан; приложение зашифрует его при запуске." -ForegroundColor Cyan
  }
  finally {
    if ($keyPointer -ne [IntPtr]::Zero) {
      [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
    }
    $keyText = $null
    $secureKey.Dispose()
    if (Test-Path -LiteralPath $temporaryKeyFile) {
      Remove-Item -LiteralPath $temporaryKeyFile -Force -ErrorAction SilentlyContinue
    }
  }
}

try {
  Adb -ArgsList @("-s", $Tv, "shell", "am", "force-stop", $Package)
  Write-Host "Models staged and verified. Vosk will import on app launch." -ForegroundColor Green
  Write-Host "GigaAM is configured as primary recognizer; Vosk is the fallback."
  Adb -ArgsList @("-s", $Tv, "shell", "monkey", "-p", $Package, "1")
  if ($remoteKeyStaged) {
    $imported = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
      Start-Sleep -Milliseconds 700
      $ack = @(& adb -s $Tv shell cat $remoteKeyStatus 2>$null) -join ""
      if ($LASTEXITCODE -ne 0) { continue }
      if ($ack.Trim() -eq "ok") { $imported = $true; break }
      if ($ack.Trim() -eq "error") { throw "Приложение не смогло сохранить Authorization Key." }
    }
    if (-not $imported) { throw "Нет подтверждения сохранения ключа от приложения." }
    Write-Host "Authorization Key проверен приложением и сохранён в Android Keystore." -ForegroundColor Green
  }
}
finally {
  if ($remoteKeyStaged) {
    # Do not leave an unencrypted bootstrap file behind if startup/import failed.
    & adb -s $Tv shell rm -f $remoteKey $remoteKeyStatus 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "Не удалось удалить временный файл ключа на ТВ. Повторно подключите ADB и удалите: $remoteKey"
    }
  }
}

if ($historyStaged) {
  & adb -s $Tv shell test -e $remoteHistory 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) {
    Write-Warning "История ожидает импорта приложением. Старые данные 0.5.2 не удалены."
  } else {
    Write-Host "История 0.5.2 импортирована." -ForegroundColor Green
  }
}
Write-Host "Aerial Voice установлен. Старое приложение 0.5.2 не удалялось." -ForegroundColor Green
