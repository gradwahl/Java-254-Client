@echo off
setlocal
cd /d "%~dp0"
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
if not exist target\Progressive-Java-Client.jar call build.bat
if not exist target\Progressive-Java-Updater.jar call build.bat

if not exist "%SCRIPT_DIR%\logs" mkdir "%SCRIPT_DIR%\logs"

echo Starting RS2 client (HTTP :80, game :43594)...
java -Xmx1g -Dsun.java2d.noddraw=true -Drs254.logDir="%SCRIPT_DIR%\logs" --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED -XX:ErrorFile="%SCRIPT_DIR%\logs\jvm_crash_%%p.log" -jar target\Progressive-Java-Client.jar 10 0 highmem members 32

if %ERRORLEVEL% neq 0 (
    echo.
    echo Client exited with error code %ERRORLEVEL%. Check %SCRIPT_DIR%\logs for crash logs.
)
pause
