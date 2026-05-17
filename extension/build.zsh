#!/usr/bin/env zsh
set -euo pipefail

ROOT="${0:A:h}"
SRC_JAVA="$ROOT/src/main/java"
RES="$ROOT/src/main/resources"
CLASSES="$ROOT/build/classes"
OUT_EXT="$ROOT/build"
BITWIG_APP_PATH="${BITWIG_APP_PATH:-/Applications/Bitwig Studio.app}"
BW_JAR="${BITWIG_JAR:-$BITWIG_APP_PATH/Contents/Java/bitwig.jar}"

if [[ ! -r $BW_JAR ]]; then
  print -u2 "Missing Bitwig API jar: $BW_JAR"
  print -u2 "Set BITWIG_APP_PATH or BITWIG_JAR before running build.zsh."
  exit 1
fi

rm -rf "$CLASSES"
mkdir -p "$CLASSES" "$OUT_EXT"
find "$SRC_JAVA" -name '*.java' -print0 | xargs -0 javac \
  --release 8 \
  -encoding UTF-8 \
  -Xlint:-options \
  -d "$CLASSES" \
  -cp "$BW_JAR"

cp -R "$RES/META-INF" "$CLASSES/"

BWEXT="$OUT_EXT/LumaFader.bwextension"
rm -f "$BWEXT"
( cd "$CLASSES" && jar cf "$BWEXT" . )

print "Built $BWEXT"
