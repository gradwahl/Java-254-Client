#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Detect OS and pick the right LWJGL natives classifier
LWJGL_VERSION="3.3.5"
MAVEN="https://repo1.maven.org/maven2/org/lwjgl"
MODULES=(lwjgl lwjgl-opengl lwjgl-glfw)

OS="$(uname -s)"
ARCH="$(uname -m)"

if [ "$OS" = "Darwin" ]; then
    if [ "$ARCH" = "arm64" ]; then
        NATIVES_CLASSIFIER="natives-macos-arm64"
    else
        NATIVES_CLASSIFIER="natives-macos"
    fi
elif [ "$OS" = "Linux" ]; then
    NATIVES_CLASSIFIER="natives-linux"
elif [[ "$OS" == MINGW* || "$OS" == MSYS* || "$OS" == CYGWIN* ]]; then
    NATIVES_CLASSIFIER="natives-windows"
else
    echo "ERROR: Unsupported OS: $OS" >&2
    exit 1
fi

# Auto-download LWJGL natives for this platform if not present
MISSING=0
REBUILD=0
for mod in "${MODULES[@]}"; do
    jar="lib/${mod}-${LWJGL_VERSION}-${NATIVES_CLASSIFIER}.jar"
    if [ ! -f "$jar" ]; then
        MISSING=1
        break
    fi
done

if [ "$MISSING" -eq 1 ]; then
    echo "LWJGL natives ($NATIVES_CLASSIFIER) not found — downloading..."
    if ! command -v curl &>/dev/null && ! command -v wget &>/dev/null; then
        echo "ERROR: curl or wget is required to download natives." >&2
        exit 1
    fi
    for mod in "${MODULES[@]}"; do
        jar="lib/${mod}-${LWJGL_VERSION}-${NATIVES_CLASSIFIER}.jar"
        if [ ! -f "$jar" ]; then
            url="${MAVEN}/${mod}/${LWJGL_VERSION}/${mod}-${LWJGL_VERSION}-${NATIVES_CLASSIFIER}.jar"
            echo "  Downloading $(basename "$jar")..."
            if command -v curl &>/dev/null; then
                curl -fsSL -o "$jar" "$url"
            else
                wget -q -O "$jar" "$url"
            fi
        fi
    done
    echo "Natives downloaded."
    REBUILD=1
fi

case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        APP_PATH="$SCRIPT_DIR/Exe Output/Progressive Java Client/Progressive Java Client.exe"
        ;;
    Darwin)
        APP_PATH="$SCRIPT_DIR/Exe Output/Progressive Java Client.app/Contents/MacOS/Progressive Java Client"
        ;;
    Linux)
        APP_PATH="$SCRIPT_DIR/Exe Output/Progressive Java Client/bin/Progressive Java Client"
        ;;
    *)
        echo "ERROR: Unsupported OS: $OS" >&2
        exit 1
        ;;
esac

if [ ! -x "$APP_PATH" ] || [ "$REBUILD" -eq 1 ]; then
    bash build.sh
fi

echo "Starting packaged RS2 client..."
"$APP_PATH"

EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "Client exited with error code $EXIT_CODE."
fi
