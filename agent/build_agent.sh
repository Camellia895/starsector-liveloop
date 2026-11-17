#!/bin/bash
# Build the LiveLoop hotswap agent (bundles ASM core for the pump injector).
#
# Env overrides:
#   STARSECTOR_ROOT  game install root          (default C:/game/StarSector.v0.9.8a-RC8)
#   STARSECTOR_JDK   JDK dir with javac/jar     (default $STARSECTOR_ROOT/jdk-28+13/bin)
#   ASM_JAR          path to org.ow2.asm:asm jar (default ~/.m2 ... or auto-download)
#
# Output: agent/hotswap-agent.jar.next  (staged; promoted to hotswap-agent.jar
#         in place when not locked by a running game — liveloop_session.ps1 /
#         liveloop_auto.ps1 also promote it right before launch)
set -e
AG="$(cd "$(dirname "$0")" && pwd)"
ROOT="${STARSECTOR_ROOT:-C:/game/StarSector.v0.9.8a-RC8}"
JDK="${STARSECTOR_JDK:-$ROOT/jdk-28+13/bin}"
ASMOUT="$(cd "$AG/.." && pwd)/asm-bundle"

cd "$AG"
rm -rf build && mkdir -p build/classes build/merge

ASMC="${ASM_JAR:-$HOME/.m2/repository/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar}"
if [ ! -f "$ASMC" ]; then
  mkdir -p "$(dirname "$ASMC")"
  curl -sL -o "$ASMC" "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
fi

"$JDK/javac" -encoding UTF-8 --release 17 -cp "$ASMC" -d build/classes $(find src -name '*.java')

# bundle ASM core (for InjectTransformer); exclude module-info
rm -rf "$ASMOUT" && mkdir -p "$ASMOUT"
(cd "$ASMOUT" && unzip -q -o "$ASMC" 'org/objectweb/asm/*' -x '*module-info*')
mkdir -p build/merge && cp -r "$ASMOUT/org" build/merge/

mkdir -p build/mf
cat > build/mf/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Premain-Class: liveloop.agent.HotSwapAgent
Agent-Class: liveloop.agent.HotSwapAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true

EOF
"$JDK/jar" --create --file hotswap-agent.jar.next --manifest build/mf/MANIFEST.MF -C build/classes . -C build/merge org
if mv -f hotswap-agent.jar.next hotswap-agent.jar 2>/dev/null; then
  echo "BUILD_AGENT_OK (promoted): $AG/hotswap-agent.jar"
else
  echo "BUILD_AGENT_OK (staged .next; live jar locked by running game — promote before next launch): $AG/hotswap-agent.jar.next"
fi
