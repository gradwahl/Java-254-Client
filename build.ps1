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
javac -J-Xmx512m --release 17 -encoding UTF-8 -cp "lib/*" -d target/classes '@sources.txt'
$javacExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
Remove-Item sources.txt -Force
if ($javacExitCode -ne 0) {
    throw "javac failed with exit code $javacExitCode"
}

# Fold runtime dependencies and LWJGL natives into the artifact so the JAR can
# be copied and launched without a sibling lib directory.
Push-Location target/classes
try {
    Get-ChildItem ../../lib -Filter *.jar | Sort-Object Name | ForEach-Object {
        jar --extract --file $_.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to extract dependency $($_.Name)"
        }
    }
} finally {
    Pop-Location
}
Remove-Item target/classes/META-INF/MANIFEST.MF -Force -ErrorAction SilentlyContinue
Remove-Item target/classes/META-INF/*.SF -Force -ErrorAction SilentlyContinue
Remove-Item target/classes/META-INF/*.DSA -Force -ErrorAction SilentlyContinue
Remove-Item target/classes/META-INF/*.RSA -Force -ErrorAction SilentlyContinue

@"
Manifest-Version: 1.0

"@ | Set-Content -Encoding ascii target/manifest.mf

jar --create --file target/Progressive-Java-Client.jar --main-class com.gradwahl.rs254.Main --manifest target/manifest.mf -C target/classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE. Close any running client and rebuild."
}
Remove-Item target/manifest.mf

Write-Host "Build complete: target/Progressive-Java-Client.jar"

# Wrap the JAR in a single .exe with the custom icon using Launch4j.
$launch4jc = "C:\Program Files (x86)\Launch4j\launch4jc.exe"
if (Test-Path $launch4jc) {
    $jarAbsPath  = (Resolve-Path "target/Progressive-Java-Client.jar").Path
    $exeAbsPath  = (Resolve-Path "target").Path + "\Progressive-Java-Client.exe"
    $icoAbsPath  = (Resolve-Path "src/main/resources/icon.ico").Path

    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>gui</headerType>
  <jar>$jarAbsPath</jar>
  <outfile>$exeAbsPath</outfile>
  <errTitle>Progressive Java Client</errTitle>
  <icon>$icoAbsPath</icon>
  <jre>
    <path></path>
    <minVersion>17</minVersion>
    <opt>-Dsun.java2d.noddraw=true --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED</opt>
  </jre>
  <cmdLine>10 0 highmem members 32</cmdLine>
  <versionInfo>
    <fileVersion>1.0.0.0</fileVersion>
    <txtFileVersion>1.0.0.0</txtFileVersion>
    <fileDescription>Progressive Java Client</fileDescription>
    <copyright>Gradwahl</copyright>
    <productVersion>1.0.0.0</productVersion>
    <txtProductVersion>1.0.0.0</txtProductVersion>
    <productName>Progressive Java Client</productName>
    <companyName>Gradwahl</companyName>
    <internalName>Progressive-Java-Client</internalName>
    <originalFilename>Progressive-Java-Client.exe</originalFilename>
  </versionInfo>
</launch4jConfig>
"@
    $xmlPath = "target\launch4j-config.xml"
    $xml | Set-Content -Encoding UTF8 $xmlPath
    & $launch4jc $xmlPath
    Remove-Item $xmlPath -Force
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Wrapped:       target/Progressive-Java-Client.exe  (double-click to run)"
    } else {
        Write-Host "Launch4j failed (exit $LASTEXITCODE) - JAR still usable via run.bat"
    }
} else {
    Write-Host "Launch4j not found at '$launch4jc' - JAR only. Install from https://launch4j.sourceforge.net/"
}

Write-Host "Run with: run.bat"
