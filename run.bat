@echo off
setlocal
if not exist target\java-254-client.jar (
  call build.bat
)
java -Drs254.host=localhost -Drs254.port=43594 -jar target\java-254-client.jar
pause
