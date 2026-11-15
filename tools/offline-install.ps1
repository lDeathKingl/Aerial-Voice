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
  $digest = (($remoteHash -join "") -split "\\s+")[0].ToLowerInvariant()
  if ($digest -ne [string]$entry.sha256) { throw "TV SHA256 mismatch: $relative" }
}
Write-Host "Models staged and verified. Vosk will import on app launch." -ForegroundColor Green
Write-Warning "GigaAM files are staged, but runtime integration must be built before GigaAM can recognize speech."
Adb -ArgsList @("-s", $Tv, "shell", "monkey", "-p", $Package, "1")
