@echo off
setlocal
set "BASE_DIR=%~dp0"
pwsh -NoProfile -ExecutionPolicy Bypass -File "%BASE_DIR%.mvn\wrapper\maven-wrapper.ps1" %*
exit /b %ERRORLEVEL%
