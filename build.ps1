$ErrorActionPreference = "Stop"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java is not installed or is not in PATH. Install JDK 17 or newer, then reopen PowerShell."
}

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw "javac was not found. You have Java Runtime, but not the JDK. Install JDK 17 or newer."
}

Remove-Item -Recurse -Force target -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force target/classes | Out-Null

if (Test-Path src/main/resources) {
    Copy-Item -Recurse src/main/resources/* target/classes/ -Force
}

Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName | Set-Content sources.txt
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
javac --release 17 -encoding UTF-8 -cp "lib/*" -d target/classes '@sources.txt'
$javacExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($javacExitCode -ne 0) {
    throw "javac failed with exit code $javacExitCode"
}
$classpath = (Get-ChildItem lib -Filter *.jar |
    Sort-Object Name |
    ForEach-Object { "../lib/$($_.Name)" }) -join " "
@"
Manifest-Version: 1.0
Class-Path: $classpath

"@ | Set-Content -Encoding ascii target/manifest.mf

jar --create --file target/java-254-client.jar --main-class com.gradwahl.rs254.Main --manifest target/manifest.mf -C target/classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE. Close any running client and rebuild."
}
Remove-Item target/manifest.mf

Write-Host "Build complete: target/java-254-client.jar"
Write-Host "Run with: run.bat"
