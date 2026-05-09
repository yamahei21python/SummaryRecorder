#!/bin/bash
# E2Eテストランナー: 実機/エミュレータ両対応
# テスト種別: core(設定+録音UI), pipeline(録音→要約→編集→削除), heavy_pipeline(事前WAV→要約確認)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

MAESTRO="${MAESTRO:-maestro}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home}"

# 対象デバイス
DEVICE_SERIAL="${DEVICE_SERIAL:-$(detect_device)}"
export ANDROID_SERIAL="$DEVICE_SERIAL"
MAESTRO_DEVICE="--device $DEVICE_SERIAL"
ok "Target: $DEVICE_SERIAL ($(is_emulator "$DEVICE_SERIAL" && echo emulator || echo real))"

# Ctrl+Cなどでプロキシクリーン
trap cleanup_all EXIT INT TERM

# ===== 初期化 =====
init_e2e() {
  wait_for_unlock "$DEVICE_SERIAL"
  start_proxy
  set_device_proxy
  init_db
  echo ""
}

# ===== Main =====
main() {
  echo "============================================"
  echo " SummaryRecorder E2E Test Runner"
  echo "============================================"
  echo ""

  init_e2e

  TESTS=("$@")
  [ ${#TESTS[@]} -eq 0 ] && TESTS=("core")

  for test_name in "${TESTS[@]}"; do
    echo ""
    echo "=== Running: $test_name ==="

    case "$test_name" in
      core)
        # 設定UI + 録音操作 (API不要、CI可)
        clear_db
        cleanup_app
        JAVA_HOME="$JAVA_HOME" $MAESTRO --device "$DEVICE_SERIAL" test "$SCRIPT_DIR/../.maestro/e2e_core.yaml" 2>&1
        sleep 3
        check_db "core DB"
        check_wav
        ;;

      pipeline)
        # 録音→要約→詳細確認→編集→削除 (API必要)
        clear_db
        cleanup_app
        JAVA_HOME="$JAVA_HOME" $MAESTRO --device "$DEVICE_SERIAL" test "$SCRIPT_DIR/../.maestro/e2e_pipeline.yaml" 2>&1
        poll_status "ORDER BY created_at DESC LIMIT 1" 10 || true
        check_db "Pipeline final state"
        check_wav
        ;;

      heavy_pipeline)
        # 事前WAV→要約確認 (setup_heavy.shがDB+WAV準備)
        if [ ! -f "$SCRIPT_DIR/../01.femal.wav" ]; then
          warn "01.femal.wav NOT FOUND — skip"
        else
          ADB="$ADB" bash "$SCRIPT_DIR/setup_heavy.sh" 2>&1 || warn "setup_heavy.sh failed (API transient error?)"
          cleanup_app
          JAVA_HOME="$JAVA_HOME" $MAESTRO --device "$DEVICE_SERIAL" test "$SCRIPT_DIR/../.maestro/e2e_pipeline.yaml" 2>&1
          check_db "heavy_pipeline result"
          check_wav
        fi
        ;;

      *)
        fail "Unknown test: $test_name"
        ;;
    esac

    ok "$test_name completed"
  done

  echo ""
  echo "============================================"
  echo -e "${GREEN}All tests passed!${NC}"
  echo "============================================"
}

main "$@"
