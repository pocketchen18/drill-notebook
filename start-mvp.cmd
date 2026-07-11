@echo off
setlocal
set "SCRIPT=%~dp0scripts\start-mvp.ps1"

where pwsh.exe >nul 2>&1
if %errorlevel%==0 (
  pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)

set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo Drill Notebook failed to start. Exit code: %EXIT_CODE%
  pause
)
exit /b %EXIT_CODE%
