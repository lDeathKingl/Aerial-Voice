param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$Apk = "",
    [string]$BootstrapKeyFile = ""
)

$ErrorActionPreference = "Stop"
$Package = "app.orchard.tv.aerialvoice.mictest"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB was not found. Install Android Platform Tools and add adb.exe to PATH."
}

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    # Windows PowerShell 5.1 can turn adb.exe stderr into NativeCommandError.
    # Temporarily use Continue so adb's exit code and text can be handled normally.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $lines = @($output | ForEach-Object { $_.ToString() })
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $lines
    }
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $candidates = @(
        (Join-Path $PSScriptRoot "..\aerial-voice\app\build\outputs\apk\micTest\debug\app-micTest-debug.apk"),
        (Join-Path $PSScriptRoot "aerial-voice-vosk-test-debug.apk"),
        (Join-Path (Get-Location) "aerial-voice-vosk-test-debug.apk")
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Apk -or -not (Test-Path $Apk)) {
    throw "APK not found: aerial-voice-vosk-test-debug.apk. Pass its path using -Apk."
}

Write-Host "Connecting to Android TV at $Tv ..." -ForegroundColor Cyan
$connectResult = Invoke-AdbCommand -Arguments @("connect", $Tv)
$connectResult.Output | ForEach-Object { Write-Host $_ }
if ($connectResult.ExitCode -ne 0) {
    throw "Could not connect to Android TV through ADB."
}

Write-Host "Installing Aerial Voice Vosk Test..." -ForegroundColor Cyan
$installResult = Invoke-AdbCommand -Arguments @("install", "-r", $Apk)
$installResult.Output | ForEach-Object { Write-Host $_ }

if ($installResult.ExitCode -ne 0 -and (([string]::Join([Environment]::NewLine, $installResult.Output)) -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
    Write-Host "Android found an older diagnostic app signed with a different key." -ForegroundColor Yellow
    Write-Host "Only the separate test package will be removed: $Package" -ForegroundColor Yellow
    Write-Host "Its local app data will be erased. The main Aerial Voice app will NOT be touched." -ForegroundColor Yellow
    $confirmation = Read-Host "Remove the old test app and install Vosk Test? (Y/N)"
    if ($confirmation -notmatch '^(?i:y|yes|д|да)$') {
        throw "Cancelled. No app was uninstalled."
    }

    Write-Host "Removing only the old diagnostic package..." -ForegroundColor Cyan
    $uninstallResult = Invoke-AdbCommand -Arguments @("uninstall", $Package)
    $uninstallResult.Output | ForEach-Object { Write-Host $_ }
    if ($uninstallResult.ExitCode -ne 0) {
        throw "Could not uninstall the old diagnostic package. The main Aerial Voice app was not changed."
    }

    Write-Host "Installing Vosk Test after removing the old test copy..." -ForegroundColor Cyan
    $installResult = Invoke-AdbCommand -Arguments @("install", $Apk)
    $installResult.Output | ForEach-Object { Write-Host $_ }
    if ($installResult.ExitCode -ne 0) {
        throw "Installation failed after removing the diagnostic package. The main Aerial Voice app was not changed."
    }
}
elseif ($installResult.ExitCode -ne 0) {
    throw "Installation failed for a reason other than a signing conflict. Check the ADB output above."
}

if (-not [string]::IsNullOrWhiteSpace($BootstrapKeyFile)) {
    if (-not (Test-Path -LiteralPath $BootstrapKeyFile)) {
        throw "Bootstrap key file not found: $BootstrapKeyFile"
    }

    $RemoteBootstrapDir = "/sdcard/Android/data/$Package/files"
    $RemoteBootstrapFile = "$RemoteBootstrapDir/aerial_voice_bootstrap_key.txt"
    Write-Host "Transferring one-time GigaChat setup to Android TV..." -ForegroundColor Cyan
    $mkdirResult = Invoke-AdbCommand -Arguments @("shell", "mkdir", "-p", $RemoteBootstrapDir)
    if ($mkdirResult.ExitCode -ne 0) {
        $mkdirResult.Output | ForEach-Object { Write-Host $_ }
        throw "Could not prepare the temporary app storage on Android TV."
    }

    $pushResult = Invoke-AdbCommand -Arguments @("push", $BootstrapKeyFile, $RemoteBootstrapFile)
    $pushResult.Output | ForEach-Object { Write-Host $_ }
    if ($pushResult.ExitCode -ne 0) {
        throw "Could not transfer the one-time GigaChat setup file."
    }
}

Write-Host "Granting microphone permission..." -ForegroundColor Cyan
$permissionResult = Invoke-AdbCommand -Arguments @("shell", "pm", "grant", $Package, "android.permission.RECORD_AUDIO")
$permissionResult.Output | ForEach-Object { Write-Host $_ }
if ($permissionResult.ExitCode -ne 0) {
    Write-Warning "Could not grant RECORD_AUDIO automatically. Allow microphone permission on the TV."
}

Write-Host "Launching diagnostic app..." -ForegroundColor Cyan
$launchResult = Invoke-AdbCommand -Arguments @("shell", "monkey", "-p", $Package, "1")
$launchResult.Output | ForEach-Object { Write-Host $_ }
if ($launchResult.ExitCode -ne 0) {
    Write-Warning "The app was installed, but automatic launch failed. Open Aerial Voice Vosk Test from the TV app list."
}

Start-Sleep -Seconds 2
Write-Host "Installed package version:" -ForegroundColor Green
$versionResult = Invoke-AdbCommand -Arguments @("shell", "dumpsys", "package", $Package)
$versionResult.Output | Where-Object { $_ -match "versionName|versionCode" } | ForEach-Object { Write-Host $_ }

if (-not [string]::IsNullOrWhiteSpace($BootstrapKeyFile)) {
    Write-Host "Waiting for Aerial Voice to encrypt and import the one-time key..." -ForegroundColor Cyan
    $imported = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Seconds 1
        $checkResult = Invoke-AdbCommand -Arguments @("shell", "test", "-e", $RemoteBootstrapFile)
        if ($checkResult.ExitCode -ne 0) {
            $imported = $true
            break
        }
    }
    if (-not $imported) {
        throw "The temporary key file is still on the TV. Check the app screen for an import error; do not share the key file."
    }
    Write-Host "GigaChat key was imported and the temporary TV file was removed." -ForegroundColor Green
}

Write-Host "Done. Only the separate diagnostic package was installed. The main Aerial Voice app and other apps were not changed." -ForegroundColor Green
