#!/usr/bin/env bash
# 离线编译 NPS · Minecraft（不经过 Gradle / Fabric maven）。
#
# 为什么存在：maven.fabricmc.net 在部分网络下只有几 KB/s 且会停住，Gradle 无法完成依赖解析。
# 本机已装好的 Minecraft 里恰好有编译所需的一切 —— 客户端 jar、fabric-loader、Fabric API 模块、
# 全部第三方库，所以直接拿它们当 classpath 调 javac 即可。
#
# 用法：
#   JAVA_HOME=/path/to/jdk-25 ./tools/build-offline.sh
# 可选环境变量：
#   MC_VERSION_DIR  版本目录（默认 C:/Users/<你>/.minecraft/versions/26.1.2-Fabric 0.19.3）
#   MC_LIBRARIES    库目录（默认 <MC_ROOT>/libraries）
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

: "${JAVA_HOME:?请设置 JAVA_HOME 指向 JDK 25}"
MC_ROOT="${MC_ROOT:-D:/Meng/Documents/PCL2/.minecraft}"
MC_VERSION_DIR="${MC_VERSION_DIR:-$MC_ROOT/versions/26.1.2-Fabric 0.19.3}"
MC_LIBRARIES="${MC_LIBRARIES:-$MC_ROOT/libraries}"

CLIENT_JAR="$MC_VERSION_DIR/$(basename "$MC_VERSION_DIR").jar"
LOADER_JAR="$MC_LIBRARIES/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
MODS_DIR="$MC_VERSION_DIR/.fabric/processedMods"

for f in "$CLIENT_JAR" "$LOADER_JAR" "$MODS_DIR"; do
  [ -e "$f" ] || { echo "缺少 $f —— 请先安装并启动过一次该整合包" >&2; exit 1; }
done

VERSION="$(grep '^mod_version=' gradle.properties | cut -d= -f2)"
BUILD="$PROJECT_DIR/build/offline"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/gen" build/libs

# ---- classpath：客户端 + loader + Fabric API 模块 + 全部第三方库 ----
cp_entries="$CLIENT_JAR;$LOADER_JAR"
while IFS= read -r jar; do cp_entries="$cp_entries;$jar"; done < <(find "$MODS_DIR" -name '*.jar' | sort)
while IFS= read -r jar; do cp_entries="$cp_entries;$jar"; done < <(find "$MC_LIBRARIES" -name '*.jar' | sort)

# 参数写进 argfile：库有上千个，命令行会超长
{
  printf -- '-classpath "%s"\n' "$cp_entries"
} > "$BUILD/javac.args"

# ---- 编译 ----
find src/main/java -name '*.java' | sort > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$BUILD/classes" @"$BUILD/javac.args" @"$BUILD/sources.txt"

# ---- 资源与清单（fabric.mod.json 的 ${version} 在这里替换）----
cp -r src/main/resources/. "$BUILD/classes/"
sed -e "s/\${version}/$VERSION/" src/main/resources/fabric.mod.json > "$BUILD/classes/fabric.mod.json"

# ---- 打包 ----
OUT="$PROJECT_DIR/build/libs/nps-minecraft-$VERSION.jar"
rm -f "$OUT"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C "$BUILD/classes" .
echo "已生成：$OUT"
