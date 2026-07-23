#!/bin/sh
# Build the rockpool UI RPM (aarch64) with the Sailfish Platform SDK in docker.
# The SDK image is x86_64-only; on Apple silicon it runs via emulation (slow but works).
# The daemon RPM is built separately: libpebble3d/package.sh
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${IMAGE:-coderus/sailfishos-platform-sdk:5.0.0.43}"
TARGET="${TARGET:-SailfishOS-5.0.0.43-aarch64}"

docker run --rm --platform linux/amd64 \
    -v "$HERE":/home/mersdk/project \
    -w /home/mersdk/project \
    "$IMAGE" \
    mb2 -t "$TARGET" build

echo "== done:"
ls -lh "$HERE/RPMS/"rockpool-*.rpm
