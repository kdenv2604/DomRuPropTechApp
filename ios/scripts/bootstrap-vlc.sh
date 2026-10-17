#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
archive="Vendor/MobileVLCKit-3.7.3.tar.xz"
checksum="0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9"
mkdir -p Vendor
if [ ! -f "$archive" ]; then
  curl --fail --location --retry 2 --output "$archive.partial" \
    "https://download.videolan.org/pub/cocoapods/prod/MobileVLCKit-3.7.3-319ed2c0-79128878.tar.xz"
  printf '%s  %s\n' "$checksum" "$archive.partial" | shasum -a 256 -c -
  mv "$archive.partial" "$archive"
fi
printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 -c -
tar -xf "$archive" -C Vendor MobileVLCKit-binary/MobileVLCKit.xcframework MobileVLCKit-binary/COPYING.txt
mkdir -p Resources
cp Vendor/MobileVLCKit-binary/COPYING.txt Resources/MobileVLCKit-COPYING.txt
