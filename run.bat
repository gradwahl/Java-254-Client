@echo off
setlocal
cd /d "%~dp0"
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "APP_EXE=%SCRIPT_DIR%\Exe Output\Progressive Java Client\Progressive Java Client.exe"

if not exist "%APP_EXE%" call build.bat
if errorlevel 1 exit /b %ERRORLEVEL%

echo Starting packaged RS2 client...
"%APP_EXE%"

if %ERRORLEVEL% neq 0 (
    echo.
    echo Client exited with error code %ERRORLEVEL%.
)
pause
