#!/usr/bin/env bash
# 本机无头构建脚本
#
#   bash scripts/local-build.sh          # debug
#   bash scripts/local-build.sh release  # release（无签名配置时跳过签名）
#
# 工具链自动探测：优先用本机 Android Studio 自带 JDK + 独立 SDK（C:\Android），
# 找不到再退回隔离目录（~/.workbuddy/binaries）。Gradle 优先用 wrapper 缓存里的 8.11.1。
set -euo pipefail

# 会话 PATH 可能为空，补上最基本的命令（dirname/find 等）
for d in "$HOME/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin" \
         "/c/Users/83866/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin"; do
  if [ -d "$d" ]; then
    export PATH="$d:/c/Windows/System32:/usr/bin:/bin:$PATH"
    break
  fi
done

pick_dir() { for d in "$@"; do [ -d "$d" ] && { printf '%s' "$d"; return; }; done; }

JDK_DIR="$(pick_dir "C:/Android/android-studio/jbr" "$HOME/.workbuddy/binaries/jdk17/jdk-17.0.2")"
SDK_DIR="$(pick_dir "C:/Android/sdk" "$HOME/.workbuddy/binaries/android-sdk")"

if [ -z "$JDK_DIR" ] || [ -z "$SDK_DIR" ]; then
  echo "找不到 JDK 或 Android SDK（JDK='$JDK_DIR' SDK='$SDK_DIR'）"; exit 1
fi

export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

GRADLE=""
for g in "$HOME"/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle.bat \
         "$HOME/.workbuddy/binaries/gradle-dist/gradle-8.11.1/bin/gradle.bat"; do
  [ -f "$g" ] && { GRADLE="$g"; break; }
done
[ -n "$GRADLE" ] || { echo "找不到 Gradle 8.11.1（wrapper 缓存或隔离目录）"; exit 1; }

TASK=":app:assembleDebug"
if [ "${1:-}" = "release" ]; then
  TASK=":app:assembleRelease"
fi

cd "${BASH_SOURCE[0]%/*}/.."
echo ">> JAVA_HOME    = $JAVA_HOME"
echo ">> ANDROID_HOME = $ANDROID_HOME"
echo ">> GRADLE       = $GRADLE"
echo ">> 任务         = $TASK"
echo

"$GRADLE" --console=plain "$TASK"

echo
echo ">> 产物："
find app/build/outputs -name "*.apk" -exec ls -la {} \;
