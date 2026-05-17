#!/bin/bash
# Install CircuitPython 9.x-compatible libs onto LUMAFADER or CIRCUITPY.
# Run after upgrading the CircuitPython UF2 to 9.x.

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE_ZIP="${TMPDIR:-/tmp}/cp9-bundle.zip"
BUNDLE_DIR="${TMPDIR:-/tmp}/cp9-bundle"

if [ -d "/Volumes/LUMAFADER" ]; then
  TARGET="/Volumes/LUMAFADER/lib"
elif [ -d "/Volumes/CIRCUITPY" ]; then
  TARGET="/Volumes/CIRCUITPY/lib"
else
  echo "Plug in the LumaFader (LUMAFADER or CIRCUITPY volume not found)."
  exit 1
fi

echo "Downloading Adafruit CircuitPython 9.x bundle..."
curl -fL -o "$BUNDLE_ZIP" \
  "https://github.com/adafruit/Adafruit_CircuitPython_Bundle/releases/download/20260508/adafruit-circuitpython-bundle-9.x-mpy-20260508.zip"
unzip -q -o "$BUNDLE_ZIP" -d "$BUNDLE_DIR"
LIB="$BUNDLE_DIR/adafruit-circuitpython-bundle-9.x-mpy-20260508/lib"

echo "Installing to $TARGET ..."
rm -rf "$TARGET"/*
cp -R "$LIB/adafruit_bus_device" "$LIB/adafruit_midi" "$LIB/adafruit_register" "$TARGET/"
cp "$LIB/adafruit_debouncer.mpy" "$LIB/neopixel.mpy" "$LIB/adafruit_ticks.mpy" "$TARGET/"

echo "Done. Reset the device (unplug/replug or press RESET on the Pico)."
