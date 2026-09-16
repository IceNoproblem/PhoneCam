#!/bin/bash
# 双击我即可停止推流：释放手机相机、断开 USB 转发，手机屏幕亮度恢复正常
cd "$(dirname "$0")"
./phone-cam.sh --stop
echo ""
echo "────────────────────────────"
echo "按任意键关闭窗口"
read -n 1 -s
