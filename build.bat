@echo off
setlocal

where java >nul 2>nul
if errorlevel 1 (
  echo Java is not installed or is not in PATH.
  echo Install JDK 17 or newer, then reopen PowerShell/CMD.
  pause
  exit /b 1
)

where javac >nul 2>nul
if errorlevel 1 (
  echo javac was not found. You have Java Runtime, but not the JDK.
  echo Install JDK 17 or newer, then reopen PowerShell/CMD.
  pause
  exit /b 1
)

if exist out rmdir /s /q out
if exist target rmdir /s /q target
mkdir out
mkdir target

echo Compiling Java files...
dir /s /b src\main\java\*.java > sources.txt
javac -encoding UTF-8 --release 17 -d out @sources.txt
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

echo Creating runnable jar...
jar cfe target\java-254-client.jar com.gradwahl.rs254.Main -C out .
if errorlevel 1 (
  echo Jar creation failed.
  pause
  exit /b 1
)

echo.
echo Build complete: target\java-254-client.jar
echo Run it with: run.bat
echo.
pause
