#!/bin/bash
# Heavy E2E実行スクリプト
# 前提: setup_heavy.sh 実行済み、実際のAPI keys設定済み

set -euo pipefail

echo "=== Heavy E2E (Full Pipeline: Groq + Gemini) ==="

# APKビルド & インストール
echo "📦 APKビルド..."
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=$HOME/Library/Android/sdk \
  ./gradlew assembleDebug --no-daemon -q

echo "📱 APKインストール..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

# セットアップ実行
echo ""
./e2e/setup_heavy.sh

# Maestro実行
echo ""
echo "🎬 Maestro実行..."
maestro test .maestro/e2e_heavy_pipeline.yaml

echo ""
echo "=== Heavy E2E完了 ==="
