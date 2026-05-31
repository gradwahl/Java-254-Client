@echo off
setlocal
powershell -ExecutionPolicy Bypass -File build.ps1
if errorlevel 1 pause
