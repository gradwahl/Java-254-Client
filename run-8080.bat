@echo off
setlocal
if not exist target\java-254-client.jar (
  call build.bat
)
echo Trying custom web port 8080 and game TCP 43594...
java -Drs254.host=localhost -Drs254.httpPort=8080 -Drs254.gamePort=43594 -jar target\java-254-client.jar
pause
