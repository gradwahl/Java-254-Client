$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$jar = "Jar Output\Progressive-Java-Client.jar"

if (-not (Test-Path $jar)) {
    Write-Host "JAR not found - building first..."
    & "$PSScriptRoot\build.ps1"
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }
}

function Find-JavaHome {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCmd) {
        $ver = (& $javacCmd.Source -version 2>&1) -replace 'javac ', ''
        $major = [int]($ver -split '[\._]')[0]
        if ($major -ge 17) { return (Split-Path (Split-Path $javacCmd.Source)) }
    }
    $roots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto",
        "${env:ProgramFiles(x86)}\Java"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory | Sort-Object Name -Descending | ForEach-Object {
            $javacBin = Join-Path $_.FullName "bin\javac.exe"
            if (Test-Path $javacBin) {
                $ver = (& $javacBin -version 2>&1) -replace 'javac ', ''
                $major = [int]($ver -split '[\._]')[0]
                if ($major -ge 17) { return $_.FullName }
            }
        }
    }
    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    throw "No JDK 17 or newer found. Download from https://adoptium.net"
}

$javawBin = Join-Path $javaHome "bin\javaw.exe"
if (-not (Test-Path $javawBin)) {
    $javawBin = Join-Path $javaHome "bin\java.exe"
}

Write-Host "Launching with $javawBin"

$jvmArgs = @(
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "-Dsun.java2d.noddraw=true",
    "-jar",
    $jar
)
& $javawBin @jvmArgs
