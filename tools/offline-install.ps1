# Offline installer: no TV-side network, no app/data removal.
param(
  [string]$Tv = "192.168.2.89:5555",
  [string]$Apk = (Join-Path $PSScriptRoot "aerial-voice-mictest06-debug.apk"),
  [string]$Models = (Join-Path $PSScriptRoot "offline-models"),
  [string]$Package = "app.orchard.tv.aerialvoice.mictest06"
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
# adb install -r preserves app data; on signing mismatch it fails without deleting anything.
Adb -ArgsList @("-s", $Tv, "install", "-r", $Apk)
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
# One-time GigaChat key bootstrap. The key is NEVER embedded in APK,
# printed, added to command-line parameters or committed to GitHub.
# The app imports it on launch, stores it in Android Keystore and deletes the file.
$remoteKey = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.txt"
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
    Adb -ArgsList @("-s", $Tv, "push", $temporaryKeyFile, $remoteKey)
    $remoteKeyStaged = $true
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
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
      Start-Sleep -Milliseconds 700
      # An exit code of 1 from test -e means the file was removed by the app.
      & adb -s $Tv shell test -e $remoteKey 2>$null | Out-Null
      if ($LASTEXITCODE -eq 1) { $imported = $true; break }
      if ($LASTEXITCODE -ne 0) { throw "Не удалось проверить импорт Authorization Key через ADB." }
    }
    if (-not $imported) {
      throw "Приложение не подтвердило импорт ключа. Временный файл будет удален; проверьте настройки GigaChat."
    }
    Write-Host "Authorization Key импортирован приложением и сохранен в Android Keystore." -ForegroundColor Green
  }
}
finally {
  if ($remoteKeyStaged) {
    # Do not leave an unencrypted bootstrap file behind if startup/import failed.
    & adb -s $Tv shell rm -f $remoteKey 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "Не удалось удалить временный файл ключа на ТВ. Повторно подключите ADB и удалите: $remoteKey"
    }
  }
}
