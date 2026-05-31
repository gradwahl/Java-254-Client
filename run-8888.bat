@echo off
setlocal
if not exist target\java-254-client.jar (
  call build.bat
)
echo Trying Linux/default web port 8888 and game TCP 43594...
java -Drs254.host=localhost -Drs254.httpPort=8888 -Drs254.gamePort=43594 -jar target\java-254-client.jar
pause
