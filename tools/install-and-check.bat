@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo Aerial Voice 0.6.1 - установка Android TV
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-full-assistant.ps1" %*
set "STATUS=%ERRORLEVEL%"
echo.
if not "%STATUS%"=="0" (
  echo Установка завершилась с ошибкой %STATUS%. Старые приложения скрипт не удаляет.
) else (
  echo Установщик завершил выполнение. Проверь работу на телевизоре.
)
echo.
pause
exit /b %STATUS%
