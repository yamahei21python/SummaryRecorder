#!/bin/bash
# Heavy E2E setup: DB + WAV事前投入
# 前提: debug APKがインストール済み、実機/エミュレータが接続済み
#
# 使用方法:
#   ./e2e/setup_heavy.sh [session_id]
#
# session_id省略時は自動生成

set -euo pipefail

SESSION_ID="${1:-e2e-test-$(date +%s)}"
PACKAGE="com.kohei.summaryrecorder"
DB_NAME="summary_recorder.db"
RECORDINGS_DIR="/data/data/$PACKAGE/files/recordings"
NOW_MS="$(date +%s)000"

echo "=== Heavy E2E Setup ==="
echo "Session ID: $SESSION_ID"

TEST_WAV="${PROJECT_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}/01.femal.wav"

# ===== 1. テスト用WAVファイル準備 =====
if [ ! -f "$TEST_WAV" ]; then
    echo "⚠️  $TEST_WAV が見つかりません"
    echo "   日本語音声のWAVファイルをプロジェクトルートに配置してください"
    exit 1
fi

# ===== 2. DB内の既存データをクリーン =====
echo "🧹 既存DBデータをクリーンアップ..."
adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"DELETE FROM summaries;\"" 2>/dev/null || true
echo "✅ クリーンアップ完了"

# ===== 3. 録音ディレクトリ作成 + WAVファイルpush =====
echo "📁 録音ディレクトリ作成..."
adb shell "mkdir -p $RECORDINGS_DIR"

echo "📤 WAVファイルpush..."
adb push "$TEST_WAV" "$RECORDINGS_DIR/$SESSION_ID.wav"

# ===== 4. DBにテストデータ投入 =====
echo "💾 DBにテストデータ投入..."

# summary: RECORDED状態でinsert (Groq転写→Gemini要約待ち)
# summaries: session_id, created_at, title, summary_text, transcription_text, audio_file_path, duration_ms, status, is_read, error_message
adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"INSERT INTO summaries VALUES ('$SESSION_ID', $NOW_MS, '', '', '', '$RECORDINGS_DIR/$SESSION_ID.wav', 6000, 'RECORDED', 0, NULL);\""

echo "✅ DB投入完了"

# ===== 5. 確認 =====
echo ""
echo "=== 投入データ確認 ==="
echo "Summaries:"
adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"SELECT session_id, status, duration_ms FROM summaries WHERE session_id='$SESSION_ID';\"" 2>/dev/null || true

echo ""
echo "=== Setup完了 ==="
echo ""

# ===== 6. Pipeline trigger =====
echo "=== Triggering pipeline (Groq → Gemini) ==="
adb shell am start -n $PACKAGE/.ui.MainActivity
sleep 3
adb shell am broadcast -n $PACKAGE/.debug.DebugPipelineReceiver -a $PACKAGE.DEBUG_TRIGGER_PIPELINE
echo "Broadcast sent. Waiting for pipeline to complete..."

# Pipeline完了待ち (最大180秒)
for i in $(seq 1 90); do
  STATUS=$(adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"SELECT status FROM summaries WHERE session_id='$SESSION_ID';\"" 2>/dev/null | tr -d '\r\n')
  if [ "$STATUS" = "DONE" ]; then
    echo "✅ Pipeline completed (${i}s) — status: DONE"
    break
  fi
  if [ "$STATUS" = "ERROR" ]; then
    echo "❌ Pipeline failed — status: ERROR"
    adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"SELECT error_message FROM summaries WHERE session_id='$SESSION_ID';\""
    exit 1
  fi
  sleep 2
done

# 結果表示
echo ""
echo "=== Result ==="
adb shell "sqlite3 /data/data/$PACKAGE/databases/$DB_NAME \"SELECT session_id, status, title, substr(summary_text,1,100) FROM summaries WHERE session_id='$SESSION_ID';\""
echo ""
echo "次のコマンドでHeavy E2EをUI検証:"
echo "  maestro test .maestro/e2e_heavy_pipeline.yaml"
