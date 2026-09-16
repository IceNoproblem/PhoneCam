#!/bin/bash
# PhoneCam 构建脚本 —— 不依赖 Gradle，直接用 Android SDK 工具链
# 流程: aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
#
# 依赖:
#   - Android SDK (build-tools 30.0.3 + platforms;android-30)
#   - JDK 11+（javac/d8/keytool）
#
# 环境变量可覆盖默认路径:
#   ANDROID_HOME  SDK 根目录（默认 ~/android-sdk 或 ~/Library/Android/sdk）
#   JAVA_HOME     JDK 根目录（默认 /usr/libexec/java_home）
set -e

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
[ -d "$ANDROID_HOME" ] || ANDROID_HOME="$HOME/Library/Android/sdk"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "✗ 找不到 JDK，请设置 JAVA_HOME（需要 11+）" >&2
  exit 1
fi
BT="$ANDROID_HOME/build-tools/30.0.3"
PLATFORM="$ANDROID_HOME/platforms/android-30/android.jar"
if [ ! -f "$PLATFORM" ]; then
  echo "✗ 缺少 $PLATFORM" >&2
  echo "  用 sdkmanager 安装: sdkmanager 'platforms;android-30' 'build-tools;30.0.3'" >&2
  exit 1
fi

cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes build/dex

echo "==> 1/6 链接资源与清单"
"$BT/aapt2" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 30 \
  --target-sdk-version 30 \
  --version-code 2 \
  --version-name 1.1 \
  --debug-mode

echo "==> 2/6 编译 Java"
# 落盘日志 + 检查退出码，别用管道把 javac 的失败吞掉
if ! "$JAVA_HOME/bin/javac" \
      -source 8 -target 8 -nowarn \
      -bootclasspath "$PLATFORM" \
      -d build/classes \
      $(find src -name '*.java') 2>build/javac.log; then
  grep -v "bootstrap class path" build/javac.log || true
  echo "✗ Java 编译失败"
  exit 1
fi
grep -v "bootstrap class path" build/javac.log || true

echo "==> 3/6 生成 dex"
"$BT/d8" --lib "$PLATFORM" --min-api 30 --output build/dex \
  $(find build/classes -name '*.class')

echo "==> 4/6 打包"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)

echo "==> 5/6 对齐"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "==> 6/6 签名"
if [ ! -f debug.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore debug.keystore \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out phonecam.apk build/aligned.apk

echo ""
echo "构建完成: $(pwd)/phonecam.apk"
ls -la phonecam.apk
