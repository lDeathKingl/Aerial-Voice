# Aerial Voice offline installer for Windows PowerShell 5.1.
# No TV-side downloads. Existing app data is never intentionally removed.
param(
  [string]$Tv = "192.168.2.89:5555",
  [string]$Apk = (Join-Path $PSScriptRoot "aerial-voice-mictest06-debug.apk"),
  [string]$Models = (Join-Path $PSScriptRoot "offline-models"),
  [string]$Package = "app.orchard.tv.aerialvoice.mictest06",
  [switch]$SkipHistory
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
  param([string[]]$Arguments)
  & adb @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw ("ADB command failed: " + ($Arguments -join " "))
  }
}

function Invoke-AdbQuiet {
  param([string[]]$Arguments)
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $output = @(& adb @Arguments 2>$null)
    $exitCode = $LASTEXITCODE
  }
  finally {
    $ErrorActionPreference = $previousPreference
  }
  return [pscustomobject]@{
    Output = $output
    ExitCode = $exitCode
  }
}

function Get-RemoteSha256 {
  param([string]$RemotePath)

  # First use Android's SHA-256 applet, when available.
  $result = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "sha256sum", $RemotePath)
  $outputText = $result.Output -join " "
  if ($result.ExitCode -eq 0 -and $outputText -match "([0-9a-fA-F]{64})") {
    return $Matches[1].ToLowerInvariant()
  }

  # Some Android TV firmware exposes the applet only through toybox.
  $result = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "toybox", "sha256sum", $RemotePath)
  $outputText = $result.Output -join " "
  if ($result.ExitCode -eq 0 -and $outputText -match "([0-9a-fA-F]{64})") {
    return $Matches[1].ToLowerInvariant()
  }

  # Last-resort compatibility path: pull the existing file to the PC and hash it there.
  # This also distinguishes a missing remote file from an unsupported hash command.
  $exists = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "test", "-f", $RemotePath)
  if ($exists.ExitCode -ne 0) {
    return ""
  }

  $temporaryCopy = Join-Path ([IO.Path]::GetTempPath()) ("aerial-remote-check-" + [Guid]::NewGuid().ToString("N") + ".tmp")
  try {
    $pull = Invoke-AdbQuiet -Arguments @("-s", $Tv, "pull", $RemotePath, $temporaryCopy)
    if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $temporaryCopy)) {
      return ""
    }
    return (Get-FileHash -LiteralPath $temporaryCopy -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  finally {
    if (Test-Path -LiteralPath $temporaryCopy) {
      Remove-Item -LiteralPath $temporaryCopy -Force -ErrorAction SilentlyContinue
    }
  }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  throw "adb.exe was not found. Install Android Platform Tools and add adb.exe to PATH."
}
if (-not (Test-Path -LiteralPath $Apk)) {
  throw ("APK file not found: " + $Apk)
}

$manifestPath = Join-Path $Models "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) {
  throw "offline-models/manifest.json is missing."
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.format -ne "AerialVoice-offline-model-cache-v1") {
  throw "Unsupported offline model manifest."
}

$expected = @(
  "vosk/vosk-model-small-ru-0.22.zip",
  "gigaam-v3/model.int8.onnx",
  "gigaam-v3/tokens.txt"
)

Write-Host "Checking local offline model files..." -ForegroundColor Cyan
foreach ($relative in $expected) {
  $entry = @($manifest.files | Where-Object { $_.path -eq $relative }) | Select-Object -First 1
  if ($null -eq $entry) {
    throw ("Model is not listed in manifest: " + $relative)
  }
  $local = Join-Path $Models ($relative.Replace("/", [IO.Path]::DirectorySeparatorChar))
  if (-not (Test-Path -LiteralPath $local)) {
    throw ("Model file is missing: " + $local)
  }
  $file = Get-Item -LiteralPath $local
  if ([long]$file.Length -ne [long]$entry.bytes) {
    throw ("Model file size mismatch: " + $relative)
  }
  $hash = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($hash -ne [string]$entry.sha256) {
    throw ("Local SHA256 mismatch: " + $relative)
  }
  Write-Host ("OK: " + $relative) -ForegroundColor Green
}

Write-Host ("Connecting to TV: " + $Tv) -ForegroundColor Cyan
Invoke-Adb -Arguments @("connect", $Tv)
$deviceState = @(& adb -s $Tv get-state 2>$null)
if ($LASTEXITCODE -ne 0 -or ($deviceState -join "").Trim() -ne "device") {
  throw "TV is not authorized for ADB. Check Network Debugging on the TV and accept the connection prompt."
}

$beforeInstallResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "pm", "path", $Package)
$beforeInstall = $beforeInstallResult.Output
$hadNewPackage = ($beforeInstallResult.ExitCode -eq 0) -and (($beforeInstall -join "") -match "package:")

$dfResult = @(& adb -s $Tv shell df -k /data 2>$null)
if ($LASTEXITCODE -eq 0 -and $dfResult.Count -gt 1) {
  $parts = (($dfResult | Select-Object -Last 1).Trim() -split "\s+")
  if ($parts.Count -ge 4 -and $parts[3] -match "^[0-9]+$") {
    $freeKiB = [long]$parts[3]
    Write-Host ("Free space in /data: {0} MB" -f [math]::Floor($freeKiB / 1024))
    if ($freeKiB -lt (400 * 1024)) {
      throw "Less than 400 MB free on TV. Free some storage and run the installer again."
    }
  }
}

Write-Host "Installing Aerial Voice. Existing app data will be preserved..." -ForegroundColor Cyan
Invoke-Adb -Arguments @("-s", $Tv, "install", "-r", $Apk)

$permissionResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "pm", "grant", $Package, "android.permission.RECORD_AUDIO")
if ($permissionResult.ExitCode -ne 0) {
  Write-Warning "Microphone permission was not granted automatically. Check it on the TV."
}
$usageResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "appops", "set", $Package, "GET_USAGE_STATS", "allow")
if ($usageResult.ExitCode -ne 0) {
  Write-Warning "Usage access was not granted automatically. It can be enabled manually."
}

$remote = "/sdcard/Android/data/$Package/files/offline-models"
Invoke-Adb -Arguments @("-s", $Tv, "shell", "mkdir", "-p", "$remote/vosk", "$remote/gigaam-v3")

Write-Host "Copying or verifying offline speech models..." -ForegroundColor Cyan
foreach ($relative in $expected) {
  $local = Join-Path $Models ($relative.Replace("/", [IO.Path]::DirectorySeparatorChar))
  $dest = "$remote/$relative"
  $entry = @($manifest.files | Where-Object { $_.path -eq $relative }) | Select-Object -First 1
  $remoteHash = Get-RemoteSha256 -RemotePath $dest

  if ($remoteHash -eq ([string]$entry.sha256).ToLowerInvariant()) {
    Write-Host ("Already installed and verified: " + $relative) -ForegroundColor Green
    continue
  }

  Write-Host ("Copying model: " + $relative) -ForegroundColor Cyan
  Invoke-Adb -Arguments @("-s", $Tv, "push", $local, $dest)
  $remoteHash = Get-RemoteSha256 -RemotePath $dest
  if ($remoteHash -ne ([string]$entry.sha256).ToLowerInvariant()) {
    throw ("TV SHA256 verification failed: " + $relative)
  }
  Write-Host ("Verified on TV: " + $relative) -ForegroundColor Green
}

$remoteHistory = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_history.json"
$historyStaged = $false
if (-not $SkipHistory -and -not $hadNewPackage) {
  $legacyPackage = "app.orchard.tv.aerialvoice.mictest"
  $legacyResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "pm", "path", $legacyPackage)
  $legacyInstalled = $legacyResult.Output
  if ($legacyResult.ExitCode -eq 0 -and (($legacyInstalled -join "") -match "package:")) {
    try {
      $oldPrefsResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "exec-out", "run-as", $legacyPackage, "cat", "shared_prefs/aerial_voice_settings.xml")
      $oldPrefs = $oldPrefsResult.Output
      if ($oldPrefsResult.ExitCode -ne 0) {
        throw "The old app does not allow reading its settings with run-as."
      }
      $oldPrefsText = $oldPrefs -join [Environment]::NewLine
      [xml]$parsed = $oldPrefsText
      $historyNode = $parsed.SelectSingleNode("/map/string[@name='conversation_history_json']")
      if ($null -ne $historyNode -and -not [string]::IsNullOrWhiteSpace($historyNode.InnerText)) {
        $items = @(ConvertFrom-Json -InputObject $historyNode.InnerText)
        if ($items.Count -gt 30) {
          $items = @($items | Select-Object -Last 30)
        }
        $historyText = ConvertTo-Json -InputObject $items -Depth 12 -Compress
        $tmpHistory = Join-Path ([IO.Path]::GetTempPath()) ("aerial-history-" + [Guid]::NewGuid().ToString("N") + ".tmp")
        try {
          [IO.File]::WriteAllText($tmpHistory, $historyText, [Text.UTF8Encoding]::new($false))
          Invoke-Adb -Arguments @("-s", $Tv, "push", $tmpHistory, $remoteHistory)
          $historyStaged = $true
          Write-Host "Legacy conversation history staged for import." -ForegroundColor Green
        }
        finally {
          Remove-Item -LiteralPath $tmpHistory -Force -ErrorAction SilentlyContinue
        }
      }
    }
    catch {
      Write-Warning ("Legacy history was not transferred. The old app data is untouched. " + $_.Exception.Message)
    }
  }
}

$remoteKey = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.txt"
$remoteKeyStatus = "/sdcard/Android/data/$Package/files/aerial_voice_bootstrap_key.status"
$remoteKeyStaged = $false
$keyPointer = [IntPtr]::Zero
$keyText = $null
$secureKey = $null
$temporaryKeyFile = $null

try {
  Write-Host ""
  Write-Host "Optional: enter your GigaChat Authorization Key on this PC." -ForegroundColor Cyan
  Write-Host "Press Enter to skip. Offline speech recognition does not need this key." -ForegroundColor Yellow
  $secureKey = Read-Host "Authorization Key (input hidden)" -AsSecureString

  if ($null -ne $secureKey -and $secureKey.Length -gt 0) {
    $temporaryKeyFile = Join-Path ([IO.Path]::GetTempPath()) ("aerial-bootstrap-" + [Guid]::NewGuid().ToString("N") + ".tmp")
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $keyText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)

    if ([string]::IsNullOrWhiteSpace($keyText)) {
      throw "Authorization Key is empty."
    }
    if ([Text.Encoding]::UTF8.GetByteCount($keyText.Trim()) -gt 16384) {
      throw "Authorization Key is too long."
    }

    [IO.File]::WriteAllText($temporaryKeyFile, $keyText.Trim(), [Text.UTF8Encoding]::new($false))
    & adb -s $Tv shell rm -f $remoteKeyStatus 2>$null | Out-Null
    $remoteKeyStaged = $true
    Invoke-Adb -Arguments @("-s", $Tv, "push", $temporaryKeyFile, $remoteKey)
    Write-Host "Key transferred. The app will encrypt it in Android Keystore." -ForegroundColor Cyan
  }

  Invoke-Adb -Arguments @("-s", $Tv, "shell", "am", "force-stop", $Package)
  Invoke-Adb -Arguments @("-s", $Tv, "shell", "monkey", "-p", $Package, "1")

  if ($remoteKeyStaged) {
    $imported = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
      Start-Sleep -Milliseconds 700
      $ackResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "cat", $remoteKeyStatus)
      $ack = $ackResult.Output -join ""
      if ($ackResult.ExitCode -ne 0) {
        continue
      }
      if ($ack.Trim() -eq "ok") {
        $imported = $true
        break
      }
      if ($ack.Trim() -eq "error") {
        throw "The app reported that the Authorization Key could not be saved."
      }
    }
    if (-not $imported) {
      throw "The app did not confirm that the Authorization Key was saved."
    }
    Write-Host "Authorization Key was verified and saved in Android Keystore." -ForegroundColor Green
  }
}
finally {
  if ($keyPointer -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
  }
  $keyText = $null
  if ($null -ne $secureKey) {
    $secureKey.Dispose()
  }
  if ($null -ne $temporaryKeyFile -and (Test-Path -LiteralPath $temporaryKeyFile)) {
    Remove-Item -LiteralPath $temporaryKeyFile -Force -ErrorAction SilentlyContinue
  }
  if ($remoteKeyStaged) {
    & adb -s $Tv shell rm -f $remoteKey $remoteKeyStatus 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "Could not remove the temporary key file from the TV. Remove it before sharing diagnostics."
    }
  }
}

if ($historyStaged) {
  $historyResult = Invoke-AdbQuiet -Arguments @("-s", $Tv, "shell", "test", "-e", $remoteHistory)
  if ($historyResult.ExitCode -eq 0) {
    Write-Warning "History is still waiting for import. The old 0.5.2 app data was not deleted."
  }
  else {
    Write-Host "Legacy history import completed." -ForegroundColor Green
  }
}

Write-Host ""
Write-Host "Aerial Voice installation finished." -ForegroundColor Green
Write-Host "The old Aerial Voice 0.5.2 app was not uninstalled." -ForegroundColor Green
