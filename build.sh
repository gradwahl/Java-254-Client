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

javac -J-Xmx1g --release 17 -encoding UTF-8 -cp "lib/*" -d target/classes @sources.txt
rm sources.txt

# Fold runtime dependencies and LWJGL natives into the artifact so the JAR can
# be copied and launched without a sibling lib directory.
for dependency in lib/*.jar; do
    (cd target/classes && jar --extract --file "../../$dependency")
done
rm -f target/classes/META-INF/MANIFEST.MF
rm -f target/classes/META-INF/*.SF target/classes/META-INF/*.DSA target/classes/META-INF/*.RSA

BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
CLIENT_VERSION="${CLIENT_VERSION:-1.6}"
CLIENT_VERSION="${CLIENT_VERSION#v}"
printf 'Manifest-Version: 1.0\nImplementation-Version: %s\nBuild-Time: %s\n\n' "$CLIENT_VERSION" "$BUILD_TIME" > target/manifest.mf

jar --create --file target/Progressive-Java-Client.jar \
    --main-class com.gradwahl.rs254.Main \
    --manifest target/manifest.mf \
    -C target/classes .

rm target/manifest.mf

echo "Build complete: target/Progressive-Java-Client.jar"
echo "Run with: ./run.sh"
