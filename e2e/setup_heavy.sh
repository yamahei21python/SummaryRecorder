#!/bin/bash
# Heavy E2E setup: DB + WAV投入 → Pipeline trigger
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

SESSION_ID="${1:-e2e-test-$(date +%s)}"
NOW_MS="$(date +%s)000"
TEST_WAV="$SCRIPT_DIR/../01.femal.wav"

echo "=== Heavy E2E Setup ==="
echo "Session: $SESSION_ID"

[ -f "$TEST_WAV" ] || fail "01.femal.wav NOT FOUND (project root)"

# DB初期化 (pull方式 or device sqlite3)
init_db

# ===== Get app data absolute path =====
APP_DATA=$($ADB shell "run-as $PACKAGE sh -c 'echo \$PWD'" | tr -d '\r\n')
WAV_PATH="$APP_DATA/files/recordings/$SESSION_ID.wav"
echo "WAV: $WAV_PATH"

# ===== DB cleanup + WAV push =====
clear_db
echo "==> WAV push..."
$ADB shell "run-as $PACKAGE mkdir -p $APP_DATA/files/recordings"
$ADB push "$TEST_WAV" "/data/local/tmp/$SESSION_ID.wav"
$ADB shell "run-as $PACKAGE cp /data/local/tmp/$SESSION_ID.wav $WAV_PATH && rm /data/local/tmp/$SESSION_ID.wav"

# ===== DB insert (broadcast方式: 実機/エミュレータ両対応) =====
echo "==> DB insert via broadcast..."
$ADB shell am broadcast -n $PACKAGE/.debug.DebugPipelineReceiver \
  -a $PACKAGE.DEBUG_INSERT_SUMMARY \
  --es session_id "$SESSION_ID" \
  --es audio_file_path "$WAV_PATH" \
  --el duration_ms 6000 2>/dev/null
sleep 2

# ===== Pipeline trigger =====
echo "==> Trigger pipeline..."
$ADB shell am start -n $PACKAGE/.ui.MainActivity
sleep 3
$ADB shell am broadcast -n $PACKAGE/.debug.DebugPipelineReceiver -a $PACKAGE.DEBUG_TRIGGER_PIPELINE
echo "Broadcast sent. Waiting..."

if ! poll_status "WHERE session_id='$SESSION_ID'" 90; then
  warn "Pipeline incomplete (transient API error?)"
  query_db "SELECT session_id, status, error_message FROM summaries WHERE session_id='$SESSION_ID';"
fi

# ===== Result =====
echo ""
query_db "SELECT session_id, status, title, substr(transcription_text,1,80) FROM summaries WHERE session_id='$SESSION_ID';"
echo ""
echo "Done. Run: maestro test .maestro/e2e_heavy_pipeline.yaml"
