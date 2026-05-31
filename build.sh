#!/usr/bin/env bash
set -e

if ! command -v java &>/dev/null; then
    echo "ERROR: Java is not installed or not in PATH. Install JDK 17 or newer." >&2
    exit 1
fi

if ! command -v javac &>/dev/null; then
    echo "ERROR: javac not found. You have a JRE, not a JDK. Install JDK 17 or newer." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

rm -rf target
mkdir -p target/classes

if [ -d src/main/resources ]; then
    cp -r src/main/resources/. target/classes/
fi

find src/main/java -name "*.java" > sources.txt

javac --release 17 -encoding UTF-8 -cp "lib/*" -d target/classes @sources.txt
rm sources.txt

CLASSPATH_ENTRIES=$(find lib -name "*.jar" | sort | while read -r jar; do echo "../lib/$(basename "$jar")"; done | tr '\n' ' ')

printf 'Manifest-Version: 1.0\nClass-Path: %s\n\n' "$CLASSPATH_ENTRIES" > target/manifest.mf

jar --create --file target/java-254-client.jar \
    --main-class com.gradwahl.rs254.Main \
    --manifest target/manifest.mf \
    -C target/classes .

rm target/manifest.mf

echo "Build complete: target/java-254-client.jar"
echo "Run with: ./run.sh"
