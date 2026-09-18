#!/usr/bin/env bash
# 本机无头构建脚本（工具链装在隔离目录，见 README 第四节）
#
#   bash scripts/local-build.sh          # debug
#   bash scripts/local-build.sh release  # release（未签名）
#
# 注意：不要给 Gradle 配公司代理 127.0.0.1:54029，守护进程会 Connection refused；
# 依赖源直连可达，故这里不设代理。
set -euo pipefail

BIN="$HOME/.workbuddy/binaries"
if command -v cygpath >/dev/null 2>&1; then
  WBIN="$(cygpath -w "$BIN")"
else
  WBIN="$BIN"
fi

export JAVA_HOME="$WBIN\\jdk17\\jdk-17.0.2"
export ANDROID_HOME="$WBIN\\android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$WBIN\\gradle-home"

GRADLE="$BIN/gradle-dist/gradle-8.11.1/bin/gradle.bat"
[ -f "$GRADLE" ] || { echo "找不到 Gradle：$GRADLE"; exit 1; }

TASK=":app:assembleDebug"
if [ "${1:-}" = "release" ]; then
  TASK=":app:assembleRelease"
fi

cd "$(dirname "$0")/.."
echo ">> JAVA_HOME        = $JAVA_HOME"
echo ">> ANDROID_HOME     = $ANDROID_HOME"
echo ">> GRADLE_USER_HOME = $GRADLE_USER_HOME"
echo ">> 任务             = $TASK"
echo

"$GRADLE" --no-daemon --console=plain "$TASK"

echo
echo ">> 产物："
find app/build/outputs -name "*.apk" -exec ls -la {} \;
