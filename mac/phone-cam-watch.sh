#!/bin/bash
# phone-cam-watch.sh — 后台守护：手机插上 USB 就自动把 OBS 画面拉起来
#
# 方案：PhoneCam 硬编 1080p60 + MPEG-TS over USB（端口 8555）
# 前台手动跑（Ctrl-C 退出），或由 launchd / 终端常驻
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SETUP="$DIR/phone-cam.sh"
PORT=8555
LOG="$DIR/watch.log"
POLL=8
MAX_LOG_LINES=2000

ADB="${ADB:-$(command -v adb 2>/dev/null || true)}"
[ -x "$ADB" ] || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
[ -x "$ADB" ] || ADB="$HOME/android-sdk/platform-tools/adb"

unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY

say() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$LOG"; }

trim_log() {
  if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt "$MAX_LOG_LINES" ]; then
    tail -n 1000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

# 采样一次流：返回 3 秒内收到的字节数
stream_bytes() {
  python3 -c "
import socket, time
try:
    s = socket.create_connection(('127.0.0.1', $PORT), timeout=4)
except Exception:
    print(0); raise SystemExit
s.settimeout(2)
n = 0; t = time.time()
try:
    while time.time() - t < 2:
        c = s.recv(65536)
        if not c: break
        n += len(c)
except Exception:
    pass
print(n)
" 2>/dev/null || echo 0
}

say "守护启动（间隔 ${POLL}s，端口 ${PORT}）"

prev=0
tick=0
while true; do
  present=0
  "$ADB" devices 2>/dev/null | grep -qw device && present=1
  tick=$((tick + 1))

  if [ "$present" -eq 1 ]; then
    [ "$prev" -eq 0 ] && say "检测到设备接入"

    pid="$("$ADB" shell "pidof com.phonecam" 2>/dev/null | tr -d '\r\n ')"
    fwd="$("$ADB" forward --list 2>/dev/null | grep "tcp:$PORT tcp:$PORT")"

    if [ -z "$pid" ] || [ -z "$fwd" ]; then
      say "推流未运行（pid=${pid:-无}，forward=$([ -n "$fwd" ] && echo 有 || echo 无)），执行修复"
      "$SETUP" >> "$LOG" 2>&1
      say "修复流程结束"
      trim_log
    elif [ $((tick % 5)) -eq 0 ]; then
      # 每 5 轮（约 40s）确认一次流真的有数据
      b="$(stream_bytes)"
      if [ "${b:-0}" -lt 50000 ]; then
        say "流无数据（${b} 字节），执行修复"
        "$SETUP" >> "$LOG" 2>&1
        say "修复流程结束"
        trim_log
      fi
    fi
  else
    [ "$prev" -eq 1 ] && say "设备已断开"
  fi

  prev=$present
  sleep "$POLL"
done
