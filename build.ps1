$ErrorActionPreference = "Stop"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java is not installed or is not in PATH. Install JDK 17 or newer, then reopen PowerShell."
}

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw "javac was not found. You have Java Runtime, but not the JDK. Install JDK 17 or newer."
}

Remove-Item -Recurse -Force out,target -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force out,target | Out-Null

Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName | Set-Content sources.txt
javac -encoding UTF-8 -source 17 -target 17 -d out '@sources.txt'
jar cfe target/java-254-client.jar com.gradwahl.rs254.Main -C out .

Write-Host "Build complete: target/java-254-client.jar"
Write-Host "Run with: java -jar target/java-254-client.jar"
