#!/bin/bash
# 从闭源 ICCOA SDK 的真 jar 生成**签名存根 jar**（:sdk-iccoa 的 compileOnly 依赖）。
#
# 为什么需要：:sdk-iccoa 的源码要引用 com.ucar.vehiclesdk.*，但闭源 jar 不进仓库
# （app/libs/icar-sdk.jar 是 gitignore 的，由使用者自己放）。存根只保留类名 / 字段
# （含常量）/ 方法签名与继承关系，方法体抽成最小合法实现 —— 能编译，但不含闭源实现。
#
# 用法：
#   tools/sdk-stubs/make-stubs.sh <真 jar> [输出 jar]
# 例：
#   tools/sdk-stubs/make-stubs.sh app/libs/icar-sdk.jar sdk-iccoa/stubs/icar-sdk-stubs.jar
#
# 依赖：JDK 17（javac/java）、ASM（脚本会自己下到 ~/项目/资料/toolchain/asm/）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IN="${1:?用法: make-stubs.sh <真 jar> [输出 jar]}"
OUT="${2:-$ROOT/sdk-iccoa/stubs/icar-sdk-stubs.jar}"
ASM_DIR="${ASM_DIR:-$HOME/项目/资料/toolchain/asm}"
ASM_JAR="$ASM_DIR/asm-9.7.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/项目/资料/toolchain/jdk-17.0.20.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -f "$ASM_JAR" ]; then
  mkdir -p "$ASM_DIR"
  echo "下载 ASM → $ASM_JAR"
  curl -sL -o "$ASM_JAR" "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar"
fi

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

javac -cp "$ASM_JAR" -d "$BUILD_DIR" "$ROOT/tools/sdk-stubs/Stubber.java"
java -cp "$ASM_JAR:$BUILD_DIR" Stubber "$IN" "$OUT"

echo "存根已更新：$OUT"
echo "校验：unzip -l '$OUT' | tail -2"
