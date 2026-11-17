#!/bin/bash
# screen_class.sh — 筛查某个核心类能否走"反编译→重编译→热替换"管线
# 用法： ./screen_class.sh com/fs/starfarer/ui/impl/ObfC1049
# 输出： PASS（可进 corepatch 热调）/ FAIL（附首个错误）
ROOT="${STARSECTOR_ROOT:-C:/game/StarSector.v0.9.8a-RC8}"
CLS="$1"; [ -z "$CLS" ] && { echo "usage: screen_class.sh <内部类路径>"; exit 2; }
J=$ROOT/jdk-28+13/bin
WORK=$ROOT/_work/explore/liveloop/screen_tmp
CP="$ROOT/starsector-core/starfarer.api.jar;$ROOT/starsector-core/starfarer_obf.jar;$ROOT/starsector-core/deobf_out/fs.common_obf.jar;$ROOT/mikohime/lwjgl.jar;$ROOT/starsector-core/lwjgl_util.jar;$ROOT/starsector-core/json.jar;$ROOT/mikohime/log4j-1.2-api-3.0.0-alpha1.jar"

rm -rf "$WORK"; mkdir -p "$WORK/src" "$WORK/out"
CLSFILE="$CLS.class"
if ! unzip -q -o -j "$ROOT/starsector-core/starfarer_obf.jar" "$CLSFILE" -d "$WORK/src" 2>/dev/null; then
  # 内部类等不存在时尝试从 jar 直接按路径取
  unzip -q -o "$ROOT/starsector-core/starfarer_obf.jar" "$CLSFILE" -d "$WORK/src_jar" 2>/dev/null || { echo "FAIL  $CLS (jar 中不存在)"; exit 1; }
  cp "$WORK/src_jar/$CLSFILE" "$WORK/src/"
fi
# 连同内部类一起抽出（外部类源码会引用它们）
BASE="${CLS%\$*}"
unzip -q -o "$ROOT/starsector-core/starfarer_obf.jar" "${BASE}\$*.class" -d "$WORK/src" 2>/dev/null
"$J/java" -jar "${VINEFLOWER_JAR:-$ROOT/_work/explore/liveloop/vineflower.jar}" "$WORK/src/$(basename $CLSFILE)" "$WORK/dec" >/dev/null 2>&1
SRC=$(find "$WORK/dec" -name "*.java" | head -1)
[ -z "$SRC" ] && { echo "FAIL  $CLS (反编译失败)"; exit 1; }
ERR=$("$J/javac" -encoding UTF-8 --release 17 -cp "$CP" -d "$WORK/out" $SRC 2>&1 | head -1)
if [ -z "$ERR" ]; then
  echo "PASS  $CLS  ->  拷贝 $SRC 到 corepatch/src/ 对应包路径即可热调"
  echo "      反编译源: $SRC"
  exit 0
else
  echo "FAIL  $CLS"
  echo "      首错: ${ERR:0:180}"
  exit 1
fi
