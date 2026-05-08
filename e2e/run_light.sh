#!/bin/bash
# Light E2E実行スクリプト (CI可)

set -euo pipefail

echo "=== Light E2E (UI Only) ==="

# APKビルド
echo "📦 APKビルド..."
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=$HOME/Library/Android/sdk \
  ./gradlew assembleDebug --no-daemon -q

# インストール
echo "📱 APKインストール..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Maestro実行
echo "🎬 Maestro実行..."
maestro test .maestro/e2e_happy_path.yaml

echo ""
echo "=== Light E2E完了 ==="
