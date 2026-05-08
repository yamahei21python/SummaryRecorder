#!/bin/bash
# SummaryRecorder: 起動/停止スクリプト
# 使い方: bash e2e/start_stop.sh [start|stop|status]

ANDROID_SDK="$HOME/Library/Android/sdk"
AVD_NAME="test_api31_arm64"
JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

export PATH="$ANDROID_SDK/platform-tools:$ANDROID_SDK/emulator:$JAVA_HOME/bin:$HOME/.maestro/bin:$PATH"

start() {
  echo "=== 起動 ==="
  echo "1) Java (Gradle): ./gradlew --no-daemon -q で自動起動"
  echo "2) エミュレータ:"
  echo "   $ANDROID_SDK/emulator/emulator -avd $AVD_NAME -no-snapshot &"
  echo "   起動待ち: adb wait-for-device"
  echo "3) APKインストール:"
  echo "   adb install -r app/build/outputs/apk/debug/app-debug.apk"

  # エミュレータ起動
  echo ""
  echo "エミュレータ起動中..."
  $ANDROID_SDK/emulator/emulator -avd $AVD_NAME -no-snapshot &
  sleep 2
  echo "adb接続待ち..."
  adb wait-for-device
  echo "エミュレータ起動完了"
}

stop() {
  echo "=== 停止 ==="
  echo "1) エミュレータ: adb emu kill"
  adb emu kill 2>/dev/null && echo "   エミュレータ停止" || echo "   エミュレータ未起動"
  echo ""
  echo "2) Gradleデーモン: ./gradlew --stop"
  $JAVA_HOME/bin/java -version 2>&1 | head -1
  pkill -f "kotlin-compiler-embeddable" 2>/dev/null && echo "   Kotlinデーモン停止" || echo "   Kotlinデーモン無し"
  pkill -f "GradleDaemon" 2>/dev/null && echo "   Gradleデーモン停止" || echo "   Gradleデーモン無し"
  echo "停止完了"
}

status() {
  echo "=== 状態確認 ==="
  adb devices | grep -v "^List\|^$"
  ps aux | grep -i "[e]mulator" | awk '{print "エミュレータ: " $NF}' || echo "エミュレータ: 停止中"
  ps aux | grep -i "[G]radleDaemon" | awk '{print "Gradle: PID " $2}' || echo "Gradle: 停止中"
  echo "JAVA_HOME: $JAVA_HOME"
}

case "${1:-status}" in
  start) start ;;
  stop)  stop ;;
  *)     status ;;
esac
