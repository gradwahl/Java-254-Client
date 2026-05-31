@echo off
setlocal
if not exist target\java-254-client.jar call build.bat

if not exist "%USERPROFILE%\.rs254" mkdir "%USERPROFILE%\.rs254"

echo Starting RS2 client (HTTP :80, game :43594)...
java -Dsun.java2d.noddraw=true --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED -XX:ErrorFile="%USERPROFILE%\.rs254\jvm_crash_%%p.log" -cp "lib\*;target\java-254-client.jar" com.gradwahl.rs254.Main 10 0 highmem members 32

if %ERRORLEVEL% neq 0 (
    echo.
    echo Client exited with error code %ERRORLEVEL%. Check %USERPROFILE%\.rs254\ for crash logs.
)
pause
