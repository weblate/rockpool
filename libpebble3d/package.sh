#!/bin/sh
# Package out/libpebble3d into an aarch64 RPM. Output: ./RPMS/
# Run ./build.sh first (or it runs automatically when out/ is missing).
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
VER="${VER:-2.0}"

if [ ! -x "$HERE/out/libpebble3d" ]; then
    echo "== no binary yet, running build.sh"
    "$HERE/build.sh"
fi

mkdir -p "$HERE/RPMS"
docker run --rm --platform linux/arm64 \
    -v "$HERE/out":/out:ro \
    -v "$HERE/rpm":/rpm:ro \
    -v "$HERE/RPMS":/RPMS \
    libpebble3d-builder sh -ec '
        mkdir -p /tmp/rpmbuild/SOURCES
        cp /rpm/libpebble3d.service /rpm/bluetooth-experimental.conf /tmp/rpmbuild/SOURCES/
        rpmbuild -bb /rpm/libpebble3d.spec \
            --define "_topdir /tmp/rpmbuild" \
            --define "ver '"$VER"'" \
            --define "debug_package %{nil}" \
            --define "_binary_payload w6.gzdio" \
            --define "__os_install_post %{nil}" \
            --define "_build_id_links none" \
            --target aarch64
        cp /tmp/rpmbuild/RPMS/aarch64/*.rpm /RPMS/
    '

echo "== done:"
ls -lh "$HERE/RPMS"/libpebble3d-*.rpm
