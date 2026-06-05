param(
    [ValidateSet("app-image", "exe", "msi")]
    [string] $Type = "app-image",

    [string] $AppVersion = "1.7",

    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$jarOutputDir = "Jar Output"
$exeOutputDir = "Exe Output"
$packageInputDir = Join-Path $jarOutputDir "jpackage-input"
$localWixBin = Join-Path $PSScriptRoot "Build Tools/WiX"

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage was not found. Install JDK 17 or newer and make sure its bin directory is in PATH."
}

if (($Type -eq "exe" -or $Type -eq "msi") -and -not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    if (Test-Path (Join-Path $localWixBin "candle.exe")) {
        $env:PATH = "$localWixBin;$env:PATH"
    }
}

if (-not $SkipBuild -or -not (Test-Path (Join-Path $jarOutputDir "Progressive-Java-Client.jar"))) {
    ./build.ps1
}

Remove-Item -Recurse -Force $packageInputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $packageInputDir | Out-Null
Copy-Item (Join-Path $jarOutputDir "Progressive-Java-Client.jar") $packageInputDir -Force
Copy-Item (Join-Path $jarOutputDir "Progressive-Java-Updater.jar") $packageInputDir -Force
Copy-Item (Join-Path $jarOutputDir "config.json") $packageInputDir -Force

Remove-Item -Recurse -Force $exeOutputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $exeOutputDir | Out-Null

$javaOptions = @(
    "-Xmx1g",
    "-Dsun.java2d.noddraw=true",
    "-Drs254.logDir=logs",
    "-XX:ErrorFile=logs\jvm_crash_%p.log",
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
)

$args = @(
    "--type", $Type,
    "--name", "Progressive Java Client",
    "--app-version", $AppVersion,
    "--vendor", "Gradwahl",
    "--description", "Progressive Java Client",
    "--dest", $exeOutputDir,
    "--input", $packageInputDir,
    "--main-jar", "Progressive-Java-Client.jar",
    "--icon", "src/main/resources/icon.ico",
    "--arguments", "10 0 highmem members 32"
)

foreach ($option in $javaOptions) {
    $args += @("--java-options", $option)
}

if ($Type -eq "exe" -or $Type -eq "msi") {
    $args += @(
        "--win-dir-chooser",
        "--win-shortcut",
        "--win-menu"
    )
}

& jpackage @args
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

Write-Host "Packaged Progressive Java Client into Exe Output using jpackage type '$Type'."
