@echo off
setlocal
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File build.ps1 %*
if errorlevel 1 pause
