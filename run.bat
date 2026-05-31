@echo off
setlocal
cd /d "%~dp0"
if not exist target\java-254-client.jar call build.bat

echo Starting RS2 client (HTTP :80, game :43594)...
java -Dsun.java2d.noddraw=true -Drs254.logDir="%~dp0" --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED -XX:ErrorFile="%~dp0jvm_crash_%%p.log" -cp "lib\*;target\java-254-client.jar" com.gradwahl.rs254.Main 10 0 highmem members 32

if %ERRORLEVEL% neq 0 (
    echo.
    echo Client exited with error code %ERRORLEVEL%. Check %~dp0 for crash logs.
)
pause
