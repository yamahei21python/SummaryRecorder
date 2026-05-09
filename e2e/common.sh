#!/bin/bash
# E2E共通関数・定数（実機＋エミュレータ両対応）
set -euo pipefail

ADB="${ADB:-/Users/kohei/Library/Android/sdk/platform-tools/adb}"
PACKAGE="com.kohei.summaryrecorder"
DB_NAME="summary_recorder.db"
PID_FILE="/tmp/e2e_proxy.pid"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()  { echo -e "${GREEN}✅${NC} $1"; }
warn() { echo -e "${YELLOW}⚠️${NC} $1"; }
fail() { echo -e "${RED}❌${NC} $1"; exit 1; }

# ===== デバイス検出 =====
detect_device() {
  local devices
  devices=$($ADB devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
  for d in $devices; do
    local props
    props=$($ADB -s "$d" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')
    if [ "$props" != "1" ]; then
      echo "$d"  # 実機のserial
      return 0
    fi
  done
  # 実機なし → 最初のエミュレータ
  echo "$devices" | head -1
}

is_emulator() {
  local serial="${1:-$(detect_device)}"
  local props
  props=$($ADB -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')
  [ "$props" = "1" ]
}

# ===== プロキシ管理 =====
PROXY_PORT=8888

start_proxy() {
  check_proxy && { ok "Proxy running"; return 0; }
  warn "Starting Node.js proxy port $PROXY_PORT..."
  mkdir -p /tmp
  cat > /tmp/proxy.mjs << 'NODESCRIPT'
import http from 'http';
import https from 'https';
import { URL } from 'url';
import net from 'net';
const s = http.createServer((req, res) => {
  const t = new URL(req.url);
  const o = { hostname: t.hostname, port: t.port || 80, path: t.pathname + t.search, method: req.method, headers: req.headers };
  http.request(o, (pr) => { res.writeHead(pr.statusCode, pr.headers); pr.pipe(res); }).on('error', () => res.writeHead(502)).end();
  req.pipe(http.request(o));
});
s.on('connect', (req, cs, head) => {
  const [h, p] = req.url.split(':'); const tp = parseInt(p) || 443;
  const ss = net.connect(tp, h, () => { cs.write('HTTP/1.1 200 OK\r\n\r\n'); ss.write(head); ss.pipe(cs); cs.pipe(ss); });
  ss.on('error', () => cs.end()); cs.on('error', () => ss.end());
});
s.listen(8888, '0.0.0.0');
NODESCRIPT
  node /tmp/proxy.mjs > /dev/null 2>&1 &
  echo $! > "$PID_FILE"
  sleep 1
  ok "Proxy started PID $(cat "$PID_FILE")"
}

stop_proxy() {
  if [ -f "$PID_FILE" ]; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
  fi
}

check_proxy() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid=$(cat "$PID_FILE" 2>/dev/null)
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# デバイスのプロキシ設定
set_device_proxy() {
  if is_emulator "$ANDROID_SERIAL"; then
    $ADB shell "settings put global http_proxy 10.0.2.2:$PROXY_PORT"
  else
    $ADB reverse tcp:$PROXY_PORT tcp:$PROXY_PORT
    $ADB shell "settings put global http_proxy 127.0.0.1:$PROXY_PORT"
  fi
  ok "Device proxy set"
}

# デバイスのプロキシ解除
clear_device_proxy() {
  $ADB shell "settings put global http_proxy :0" 2>/dev/null || true
  if ! is_emulator "$ANDROID_SERIAL"; then
    $ADB reverse --remove tcp:$PROXY_PORT 2>/dev/null || true
  fi
  ok "Device proxy cleared"
}

# 全体クリーンアップ (trap用)
cleanup_all() {
  clear_device_proxy
  stop_proxy
  if [ -n "${DB_TMP_DIR:-}" ] && [ -d "$DB_TMP_DIR" ]; then
    rm -rf "$DB_TMP_DIR" 2>/dev/null || true
  fi
  ok "Cleanup done"
}

# ===== DB操作 (実機＝pull, エミュレータ＝run-as sqlite3) =====

# ホスト側sqlite3パス
HOST_SQLITE="${HOST_SQLITE:-$(which sqlite3)}"

# DBをホストにpull (WAL対応)
pull_db_to_host() {
  local tmp_dir="${1:-/tmp/e2e_sr_$$}"
  mkdir -p "$tmp_dir"
  # Main DB + WAL + SHM を個別にpull
  $ADB exec-out "run-as $PACKAGE sh -c 'cat databases/$DB_NAME'" > "$tmp_dir/db.sqlite" 2>/dev/null
  $ADB exec-out "run-as $PACKAGE sh -c 'cat databases/$DB_NAME-wal'" > "$tmp_dir/db.sqlite-wal" 2>/dev/null
  $ADB exec-out "run-as $PACKAGE sh -c 'cat databases/$DB_NAME-shm'" > "$tmp_dir/db.sqlite-shm" 2>/dev/null
  echo "$tmp_dir"
}

# WALのみリフレッシュ（高速poll用）
refresh_db_wal() {
  local tmp_dir="$1"
  $ADB exec-out "run-as $PACKAGE sh -c 'cat databases/$DB_NAME-wal'" > "$tmp_dir/db.sqlite-wal" 2>/dev/null
}

# デバイス上でsqlite3利用可能か
has_device_sqlite3() {
  $ADB shell "which sqlite3" 2>/dev/null | grep -q sqlite3
}

# 統合query: デバイスsqlite3 or host pull
query_db() {
  local query="$1"
  local result
  if [ -n "${DB_TMP_DIR:-}" ]; then
    # Pull方式（実機）
    refresh_db_wal "$DB_TMP_DIR"
    result=$($HOST_SQLITE "$DB_TMP_DIR/db.sqlite" "$query" 2>/dev/null | tr -d '\r\n')
  else
    # run-as方式（エミュレータ）
    result=$($ADB shell "run-as $PACKAGE sqlite3 databases/$DB_NAME \"$query\"" 2>/dev/null | tr -d '\r\n')
  fi
  echo "$result"
}

# DB初期化 (1回だけpull)
init_db() {
  if has_device_sqlite3; then
    DB_TMP_DIR=""
    ok "Using device sqlite3"
  else
    DB_TMP_DIR=$(pull_db_to_host)
    ok "DB pulled to $DB_TMP_DIR (WAL mode)"
  fi
}

poll_status() {
  local where="${1:-ORDER BY created_at DESC LIMIT 1}"
  local timeout="${2:-90}"
  for i in $(seq 1 "$timeout"); do
    local status
    status=$(query_db "SELECT status FROM summaries $where")
    case "$status" in
      DONE)   ok "Pipeline DONE (${i}s)"; return 0;;
      ERROR)
        local err
        err=$(query_db "SELECT error_message FROM summaries $where")
        warn "Pipeline ERROR: $err"; return 1;;
      '')     warn "No summaries found"; return 2;;
    esac
    sleep 2
  done
  warn "Pipeline TIMEOUT (${timeout}s)"; return 2
}

check_wav() {
  echo "--- WAV files ---"
  $ADB shell "run-as $PACKAGE ls -la files/recordings/" 2>/dev/null | grep -v "^d" | head -5
  local count
  count=$($ADB shell "run-as $PACKAGE ls files/recordings/ 2>/dev/null | grep -c '\.wav'" 2>/dev/null || echo "0")
  if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -ge 1 ]; then
    ok "WAV count: $count"
  else
    warn "No WAV files"
  fi
  echo ""
}

check_db() {
  local label="$1"
  local session_id="${2:-}"
  local where
  if [ -n "$session_id" ]; then
    where="WHERE session_id='$session_id'"
  else
    where="ORDER BY created_at DESC LIMIT 1"
  fi
  echo "--- $label ---"
  query_db "SELECT session_id, status, title, duration_ms || 'ms' FROM summaries $where"
  echo ""
}

cleanup_app() {
  # 画面ON
  $ADB shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
  # ロック画面解除試行
  $ADB shell input swipe 360 1200 360 400 300 2>/dev/null || true
  sleep 0.3
  $ADB shell input swipe 360 1200 360 400 300 2>/dev/null || true
  sleep 0.3
  $ADB shell am force-stop $PACKAGE 2>/dev/null || true
}

# ロック画面チェック（実機用）
wait_for_unlock() {
  local serial="${1:-$DEVICE_SERIAL}"
  if is_emulator "$serial"; then return 0; fi
  local locked
  locked=$($ADB shell dumpsys window 2>/dev/null | grep -c 'mDreamingLockscreen=true' | tr -d '\r\n' || echo 0)
  if [ "$locked" -gt 0 ]; then
    warn "ロック画面検出 — 手動でロック解除してください"
    local retries=0
    while [ $retries -lt 30 ]; do
      sleep 2
      locked=$($ADB shell dumpsys window 2>/dev/null | grep -c 'mDreamingLockscreen=true' | tr -d '\r\n' || echo 0)
      [ "$locked" -eq 0 ] && { ok "ロック解除確認"; return 0; }
      retries=$((retries + 1))
    done
    warn "ロック解除タイムアウト — テスト継続します"
  fi
}

clear_db() {
  # Room再作成前提（DBファイル削除） + DataStore初期化
  $ADB shell "run-as $PACKAGE sh -c 'rm -f databases/$DB_NAME databases/$DB_NAME-wal databases/$DB_NAME-shm files/datastore/settings.preferences_pb'" 2>/dev/null || true
}
