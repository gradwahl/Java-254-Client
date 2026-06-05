@echo off
setlocal
powershell -ExecutionPolicy Bypass -File package.ps1 %*
if errorlevel 1 pause
