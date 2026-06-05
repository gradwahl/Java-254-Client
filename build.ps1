$ErrorActionPreference = "Stop"

$jarOutputDir = "Jar Output"
$classesDir = Join-Path $jarOutputDir "classes"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java is not installed or is not in PATH. Install JDK 17 or newer, then reopen PowerShell."
}

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw "javac was not found. You have Java Runtime, but not the JDK. Install JDK 17 or newer."
}

Remove-Item -Recurse -Force $jarOutputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesDir | Out-Null

if (Test-Path src/main/resources) {
    Copy-Item -Recurse src/main/resources/* "$classesDir/" -Force
}

$cacheFiles = @(
    "main_file_cache.dat",
    "main_file_cache.idx0",
    "main_file_cache.idx1",
    "main_file_cache.idx2",
    "main_file_cache.idx3",
    "main_file_cache.idx4"
)
$cacheOutputDir = Join-Path $classesDir "cache"
New-Item -ItemType Directory -Force $cacheOutputDir | Out-Null
foreach ($cacheFile in $cacheFiles) {
    $source = Join-Path "cache" $cacheFile
    if (Test-Path $source) {
        Copy-Item $source "$cacheOutputDir/" -Force
    }
}

Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName | Set-Content sources.txt
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
javac -J-Xmx1g --release 17 -encoding UTF-8 -cp "lib/*" -d $classesDir '@sources.txt'
$javacExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
Remove-Item sources.txt -Force
if ($javacExitCode -ne 0) {
    throw "javac failed with exit code $javacExitCode"
}

# Fold runtime dependencies and LWJGL natives into the artifact so the JAR can
# be copied and launched without a sibling lib directory.
Push-Location $classesDir
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
Remove-Item "$classesDir/META-INF/MANIFEST.MF" -Force -ErrorAction SilentlyContinue
Remove-Item "$classesDir/META-INF/*.SF" -Force -ErrorAction SilentlyContinue
Remove-Item "$classesDir/META-INF/*.DSA" -Force -ErrorAction SilentlyContinue
Remove-Item "$classesDir/META-INF/*.RSA" -Force -ErrorAction SilentlyContinue

$clientVersion = if ($env:CLIENT_VERSION) { $env:CLIENT_VERSION.TrimStart("v") } else { "1.7" }
@"
{
  "version": "$clientVersion",
  "web_host": "localhost",
  "web_port": 80,
  "game_port": 43594
}
"@ | Set-Content -Encoding UTF8 (Join-Path $jarOutputDir "config.json")
@"
Manifest-Version: 1.0
Implementation-Version: $clientVersion
Build-Time: $((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))

"@ | Set-Content -Encoding ascii (Join-Path $jarOutputDir "manifest.mf")

$updaterJar = Join-Path $jarOutputDir "Progressive-Java-Updater.jar"
$clientJar = Join-Path $jarOutputDir "Progressive-Java-Client.jar"
$manifest = Join-Path $jarOutputDir "manifest.mf"

# Build the updater jar first, then fold it into the client classes so it ships
# *inside* the client jar. At runtime the client extracts it back beside itself.
jar --create --file $updaterJar --main-class com.gradwahl.rs254.update.UpdateHelper -C $classesDir com/gradwahl/rs254/update
if ($LASTEXITCODE -ne 0) {
    throw "updater jar failed with exit code $LASTEXITCODE"
}
Copy-Item $updaterJar (Join-Path $classesDir "Progressive-Java-Updater.jar") -Force

jar --create --file $clientJar --main-class com.gradwahl.rs254.Main --manifest $manifest -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE. Close any running client and rebuild."
}
Remove-Item $manifest

Write-Host "Build complete: Jar Output/Progressive-Java-Client.jar"
Write-Host "Build complete: Jar Output/Progressive-Java-Updater.jar"
Write-Host "Run with: run.bat"
