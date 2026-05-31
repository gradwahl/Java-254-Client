@echo off
setlocal
if not exist target\java-254-client.jar call build.bat
echo Starting RS2 client (HTTP :80, game :43594)...
java -Dsun.java2d.noddraw=true --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED -cp "lib\*;target\java-254-client.jar" com.gradwahl.rs254.Main 10 0 highmem members 32
pause
