@echo off
setlocal
cd /d "%~dp0"

where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: Windows PowerShell was not found.
  pause
  exit /b 1
)

if not exist "%~dp0offline-install.ps1" (
  echo ERROR: offline-install.ps1 is missing.
  echo Extract the complete ZIP archive before running this installer.
  pause
  exit /b 1
)

echo Starting Aerial Voice offline installer...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0offline-install.ps1"
set "INSTALL_EXIT=%ERRORLEVEL%"

if not "%INSTALL_EXIT%"=="0" (
  echo.
  echo INSTALLATION FAILED. Do not uninstall the old Aerial Voice app.
  echo Copy the error text and send it to ChatGPT.
) else (
  echo.
  echo Installer finished successfully.
)

pause
exit /b %INSTALL_EXIT%
