@echo off
setlocal
if not exist target\java-254-client.jar (
  call build.bat
)
echo Using server defaults from uploaded lostcity254 engine:
echo   HTTP assets /crc: http://localhost:80/crc
echo   Game TCP:         localhost:43594
echo.
java -Drs254.host=localhost -Drs254.httpPort=80 -Drs254.gamePort=43594 -jar target\java-254-client.jar
pause
