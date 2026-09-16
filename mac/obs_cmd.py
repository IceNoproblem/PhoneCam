#!/usr/bin/env python3
"""OBS WebSocket 小工具：重连媒体源 / 抓帧。

用法:
  obs_cmd.py restart [源名]   重启媒体源（触发重新连接 tcp://127.0.0.1:8555）
  obs_cmd.py shot <输出.png> [源名]
  obs_cmd.py ls               列出输入源
"""
import asyncio
import base64
import hashlib
import json
import sys

import websockets

PW = "123456"
URL = "ws://127.0.0.1:4455"


class Obs:
    def __init__(self, ws):
        self.ws = ws
        self.n = 0

    @staticmethod
    async def connect():
        ws = await websockets.connect(URL, max_size=64 * 1024 * 1024)
        hello = json.loads(await ws.recv())
        d = hello.get("d", {})
        ident = {"rpcVersion": d.get("rpcVersion", 1)}
        auth = d.get("authentication")
        if auth:
            sec = base64.b64encode(
                hashlib.sha256((PW + auth["salt"]).encode()).digest()).decode()
            ident["authentication"] = base64.b64encode(
                hashlib.sha256((sec + auth["challenge"]).encode()).digest()).decode()
        await ws.send(json.dumps({"op": 1, "d": ident}))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("op") in (2, 9):
                break
        return Obs(ws)

    async def req(self, request_type, data=None):
        self.n += 1
        rid = str(self.n)
        await self.ws.send(json.dumps({"op": 6, "d": {
            "requestType": request_type, "requestId": rid,
            "requestData": data or {}}}))
        while True:
            msg = json.loads(await self.ws.recv())
            if msg.get("op") == 7 and msg["d"].get("requestId") == rid:
                return msg["d"]


async def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    o = await Obs.connect()

    if cmd == "ls":
        r = await o.req("GetInputList", {"inputKind": "ffmpeg_source"})
        for i in r["responseData"]["inputs"]:
            print("-", i["inputName"])
        return

    src = sys.argv[3] if len(sys.argv) > 3 else "手机画面"

    if cmd == "restart":
        r = await o.req("TriggerMediaInputAction", {
            "inputName": src,
            "mediaAction": "OBS_WEBSOCKET_MEDIA_INPUT_ACTION_RESTART"})
        print("restart:", r["requestStatus"]["result"])
        return

    if cmd == "shot":
        out = sys.argv[2]
        r = await o.req("GetSourceScreenshot", {
            "sourceName": src, "imageFormat": "png",
            "imageWidth": 1280, "imageHeight": 720})
        st = r["requestStatus"]
        if not st.get("result"):
            print("FAIL", json.dumps(r, ensure_ascii=False)[:300])
            return
        img = r["responseData"]["imageData"].split(",", 1)[1]
        open(out, "wb").write(base64.b64decode(img))
        print("OK", out)
        return

    print("未知命令", cmd)


asyncio.run(main())
