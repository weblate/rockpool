#!/bin/sh
# Build libpebble3d as a GraalVM native image. Output: ./out/libpebble3d
# MOBILEAPP=<checkout> builds from a dev checkout instead of the pinned submodule.
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
MOBILEAPP="${MOBILEAPP:-$HERE/mobileapp}"
OUT="$HERE/out"

if [ ! -d "$MOBILEAPP/libpebble3" ]; then
    echo "error: mobileapp checkout not found at $MOBILEAPP" >&2
    echo "hint: git submodule update --init libpebble3d/mobileapp (or set MOBILEAPP=...)" >&2
    exit 1
fi

# AGP needs an SDK location to configure the project even though only the jvm
# target gets built; the submodule has no local.properties.
if [ -z "$ANDROID_HOME" ] && [ ! -f "$MOBILEAPP/local.properties" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -d "$sdk" ]; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
    if [ -z "$ANDROID_HOME" ]; then
        echo "error: no Android SDK found; set ANDROID_HOME or create $MOBILEAPP/local.properties" >&2
        exit 1
    fi
fi

echo "== gradle jvmDist (daemon composite build; libpebble3 from $MOBILEAPP)"
(cd "$HERE/daemon" && MOBILEAPP="$MOBILEAPP" ./gradlew jvmDist)

echo "== builder image"
docker build --platform linux/arm64 -t libpebble3d-builder "$HERE"

echo "== trace + native-image"
mkdir -p "$OUT"
docker run --rm --platform linux/arm64 \
    -v "$HERE/daemon/build/jvmDist":/dist \
    -v "$HERE":/work \
    -v "$OUT":/out \
    libpebble3d-builder sh /work/build-native.sh

echo "== done: $OUT/libpebble3d"
