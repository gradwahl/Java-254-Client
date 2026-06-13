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

JAR_OUTPUT_DIR="Jar Output"
CLASSES_DIR="$JAR_OUTPUT_DIR/classes"

rm -rf "$JAR_OUTPUT_DIR"
mkdir -p "$CLASSES_DIR"

if [ -d src/main/resources ]; then
    cp -r src/main/resources/. "$CLASSES_DIR/"
fi

mkdir -p "$CLASSES_DIR/cache"
for cache_file in \
    main_file_cache.dat \
    main_file_cache.idx0 \
    main_file_cache.idx1 \
    main_file_cache.idx2 \
    main_file_cache.idx3 \
    main_file_cache.idx4; do
    if [ -f "cache/$cache_file" ]; then
        cp "cache/$cache_file" "$CLASSES_DIR/cache/"
    fi
done

find src/main/java -name "*.java" > sources.txt

javac -J-Xmx1g --release 17 -encoding UTF-8 -cp "lib/*" -d "$CLASSES_DIR" @sources.txt
rm sources.txt

# Fold runtime dependencies and LWJGL natives into the artifact so the JAR can
# be copied and launched without a sibling lib directory.
for dependency in lib/*.jar; do
    (cd "$CLASSES_DIR" && jar --extract --file "../../$dependency")
done
rm -f "$CLASSES_DIR/META-INF/MANIFEST.MF"
rm -f "$CLASSES_DIR"/META-INF/*.SF "$CLASSES_DIR"/META-INF/*.DSA "$CLASSES_DIR"/META-INF/*.RSA

BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
CLIENT_VERSION="${CLIENT_VERSION:-1.7}"
CLIENT_VERSION="${CLIENT_VERSION#v}"
cat > "$JAR_OUTPUT_DIR/config.json" <<EOF
{
  "version": "$CLIENT_VERSION",
  "web_host": "localhost",
  "web_port": 80,
  "game_port": 43594
}
EOF
printf 'Manifest-Version: 1.0\nImplementation-Version: %s\nBuild-Time: %s\n\n' "$CLIENT_VERSION" "$BUILD_TIME" > "$JAR_OUTPUT_DIR/manifest.mf"

# Build the updater jar first, then fold it into the client classes so it ships
# *inside* the client jar. At runtime the client extracts it back beside itself.
jar --create --file "$JAR_OUTPUT_DIR/Progressive-Java-Updater.jar" \
    --main-class com.gradwahl.rs254.update.UpdateHelper \
    -C "$CLASSES_DIR" com/gradwahl/rs254/update

cp "$JAR_OUTPUT_DIR/Progressive-Java-Updater.jar" "$CLASSES_DIR/Progressive-Java-Updater.jar"

jar --create --file "$JAR_OUTPUT_DIR/Progressive-Java-Client.jar" \
    --main-class com.gradwahl.rs254.Main \
    --manifest "$JAR_OUTPUT_DIR/manifest.mf" \
    -C "$CLASSES_DIR" .

rm "$JAR_OUTPUT_DIR/manifest.mf"

echo "Build complete: Jar Output/Progressive-Java-Client.jar"
echo "Build complete: Jar Output/Progressive-Java-Updater.jar"
echo "Run with: ./run.sh"
