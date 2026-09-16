#!/bin/bash
# ============================================================
# PhoneCam —— 手机摄像头 -> OBS (macOS / USB / 1080p60)
#
#   用法:
#     phone-cam.sh            启动（插好数据线后执行，全自动）
#     phone-cam.sh --stop     停止推流、释放相机、屏幕恢复正常
#     phone-cam.sh --status   查看当前状态（进程/连接/实测帧率）
#
# 方案：自建 PhoneCam app（Camera2 + H.264 硬件编码 + MPEG-TS/TCP）
#       1920x1080 @ 60fps，无第三方 app、无内购限制
#
# 链路：sensor -> MediaCodec 硬编 -> MPEG-TS -> TCP:8555
#         -> adb forward(USB) -> OBS 媒体源 tcp://127.0.0.1:8555
#
# 省电（由 app 负责，退出自动恢复）：
#   - 工作时把窗口亮度压到最暗，点一下屏幕临时亮 5 秒
#   - 没有客户端（OBS/自检）连接满 8 秒，自动暂停相机与编码
# ============================================================
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=8555
PKG=com.phonecam
APK="$HERE/../android/phonecam.apk"
LOG="$HERE/phonecam.log"

# ---- adb 自动发现：PATH -> 常见 SDK 位置 ----
ADB="${ADB:-$(command -v adb 2>/dev/null || true)}"
[ -x "$ADB" ] || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
[ -x "$ADB" ] || ADB="$HOME/android-sdk/platform-tools/adb"
# python：系统 python3 即可（measure.py 只用标准库）
PY="$(command -v python3 || echo /usr/bin/python3)"

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

if [ ! -x "$ADB" ]; then
  log "✗ 找不到 adb。请安装 Android platform-tools 并加入 PATH，或设 ADB=/path/to/adb"
  exit 1
fi

# 读流约 2.5 秒，返回 "帧数|秒数|Mbps|fps"
measure() { "$PY" "$HERE/measure.py" "$PORT"; }

wait_device() {
  log "等待手机接入…"
  for i in $(seq 1 30); do
    if "$ADB" devices 2>/dev/null | grep -q "device$"; then return 0; fi
    sleep 1
  done
  return 1
}

# ==================== 停止 ====================
if [ "${1:-}" = "--stop" ]; then
  "$ADB" shell "am force-stop $PKG" >/dev/null 2>&1
  "$ADB" forward --remove tcp:$PORT >/dev/null 2>&1
  log "✓ 已停止推流并释放相机（屏幕亮度恢复正常）"
  exit 0
fi

# ==================== 状态 ====================
if [ "${1:-}" = "--status" ]; then
  echo "进程      : $("$ADB" shell "pidof $PKG" 2>/dev/null | tr -d '\r' || echo '未运行')"
  echo "端口转发  : $("$ADB" forward --list 2>/dev/null | grep $PORT || echo '未建立')"
  R=$(measure)
  IFS='|' read -r FRAMES SECS MBPS FPS <<< "$R"
  if [ "${FRAMES:-0}" -gt 10 ]; then
    echo "实测视频  : ${FRAMES} 帧 / ${SECS} 秒 = ${FPS} fps, ${MBPS} Mbps"
  else
    echo "实测视频  : 无数据（可能处于省电暂停，或有其他 app 占用相机）"
  fi
  exit 0
fi

# ==================== 启动 ====================
if ! wait_device; then
  log "✗ 30 秒内未检测到设备，请插好数据线"
  exit 1
fi
log "✓ 设备已连接"

# 唤醒屏幕 + USB 连接时保持常亮（亮度由 app 压到最暗）
"$ADB" shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
"$ADB" shell "svc power stayon true" >/dev/null 2>&1

# 首次运行才安装；已装过就直接跳过
if "$ADB" shell "pm list packages" 2>/dev/null | grep -q "$PKG"; then
  log "✓ PhoneCam 已安装（跳过安装步骤）"
else
  if [ ! -f "$APK" ]; then
    log "✗ 未安装 PhoneCam 且找不到 APK: $APK"
    log "  先构建: cd android && ./build.sh"
    exit 1
  fi
  log "首次安装 PhoneCam…"
  # vivo 的安装校验器会拦 adb 安装（报 User rejected permissions）
  "$ADB" shell "settings put global verifier_verify_adb_installs 0" >/dev/null 2>&1
  "$ADB" shell "settings put global package_verifier_enable 0" >/dev/null 2>&1
  "$ADB" push "$APK" /data/local/tmp/pc.apk >/dev/null 2>&1
  "$ADB" shell "pm install -r -t /data/local/tmp/pc.apk" 2>&1 | tail -1
  log "✓ 已安装"
fi
"$ADB" shell "pm grant $PKG android.permission.CAMERA" >/dev/null 2>&1

# 释放相机（其他相机类 app 会抢占）
"$ADB" shell "am force-stop com.pas.webcam" >/dev/null 2>&1

# USB 端口转发
"$ADB" forward tcp:$PORT tcp:$PORT >/dev/null 2>&1
log "✓ 端口转发 tcp:$PORT"

# 启动推流
"$ADB" shell "am start -n $PKG/.CamServer" >/dev/null 2>&1
log "✓ PhoneCam 已启动（1080p60，屏幕已压到最暗）"

# 验证：等相机起来后实测帧率
sleep 5
R=$(measure)
IFS='|' read -r FRAMES SECS MBPS FPS <<< "$R"
if [ "${FRAMES:-0}" -gt 10 ]; then
  log "✓ 视频流正常：${FRAMES} 帧 / ${SECS} 秒 = ${FPS} fps，${MBPS} Mbps"
  log "完成。OBS 里配好的「手机画面」媒体源会自动接上。"
else
  log "⚠ 未收到视频数据，请检查手机是否被其他 app 占用相机"
  exit 2
fi
