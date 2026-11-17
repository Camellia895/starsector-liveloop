#!/bin/bash
# Build the SimArena mod jar against the game API + (optionally deobfuscated) core jars.
#
# Env overrides:
#   STARSECTOR_ROOT  game install root (default C:/game/StarSector.v0.9.8a-RC8)
#   STARSECTOR_JDK   JDK dir with javac/jar
#   LIVELOOP_DIR     this repo's checkout        (default: script's parent dir)
#
# Output: mods/SimArena/jars/SimArena.jar.next (staged; promoted when the game
#         is not running — liveloop_auto.ps1 / dcevm_auto.ps1 promote before launch)
#
# NOTE: -cp entries must be Windows-style (C:/...) — native javac cannot read MSYS /c/ paths.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIVELOOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="${STARSECTOR_ROOT:-C:/game/StarSector.v0.9.8a-RC8}"
JDK="${STARSECTOR_JDK:-$ROOT/jdk-28+13/bin}"
MOD="$ROOT/mods/SimArena"
CORE="$ROOT/starsector-core"
W="$ROOT/_work/explore/liveloop"

CP="$CORE/starfarer.api.jar"
CP="$CP;$CORE/starfarer_obf.jar"            # deobfuscated (renamed) core jar
CP="$CP;$CORE/deobf_out/fs.common_obf.jar"
CP="$CP;$ROOT/mikohime/lwjgl.jar;$CORE/lwjgl_util.jar"
CP="$CP;$CORE/json.jar"
CP="$CP;$LIVELOOP_DIR/agent/hotswap-agent.jar"
CP="$CP;$ROOT/mikohime/log4j-1.2-api-3.0.0-alpha1.jar"

cd "$MOD"
rm -rf build && mkdir -p build/classes jars

"$JDK/javac" -encoding UTF-8 --release 17 -cp "$CP" -d build/classes $(find src -name '*.java')

"$JDK/jar" --create --file jars/SimArena.jar.next -C build/classes .
if mv -f jars/SimArena.jar.next jars/SimArena.jar 2>/dev/null; then
  echo "BUILD_SIMARENA_OK (promoted)"
else
  echo "BUILD_SIMARENA_OK (staged .next; mod jar locked by running game — promote before next launch)"
fi
