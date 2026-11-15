@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0offline-install.ps1"
echo.
if errorlevel 1 echo Установка завершилась с ошибкой. Не удаляйте старое приложение.
pause
