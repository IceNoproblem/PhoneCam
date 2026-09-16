#!/usr/bin/env python3
"""读 MPEG-TS 流约 2.5 秒，输出 "帧数|秒数|Mbps|fps"。用法: measure.py [port]
（刻意不用 awk 做浮点：本机执行环境下 awk 会被杀，改由 python 直接算好）"""
import socket
import sys
import time

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8555
frames = 0
total = 0
try:
    s = socket.create_connection(("127.0.0.1", port), timeout=6)
except Exception:
    print("0|1|0|0")
    sys.exit(0)

s.settimeout(2)
t0 = time.time()
buf = b""
while time.time() - t0 < 2.5:
    try:
        chunk = s.recv(262144)
        if not chunk:
            break
        buf += chunk
        total += len(chunk)
    except Exception:
        break
elapsed = max(0.01, time.time() - t0)

pos = 0
while pos + 188 <= len(buf):
    if buf[pos] == 0x47:
        pid = ((buf[pos + 1] & 0x1F) << 8) | buf[pos + 2]
        if pid == 0x100 and (buf[pos + 1] & 0x40):
            afc = (buf[pos + 3] >> 4) & 0x3
            off = 4
            if afc == 3:
                off = 5 + buf[pos + 4]
            elif afc == 2:
                off = 188
            if off < 188 and buf[pos + off:pos + off + 4] == b"\x00\x00\x01\xe0":
                frames += 1
        pos += 188
    else:
        pos += 1

s.close()
mbps = total * 8 / elapsed / 1e6
fps = frames / elapsed
print(f"{frames}|{elapsed:.2f}|{mbps:.1f}|{fps:.1f}")
