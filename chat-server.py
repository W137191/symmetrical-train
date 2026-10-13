#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校园内网 Qwen 聊天 - 本地服务
- 页面入口:  http://localhost:8000
- 代理转发:  /v1/*  ->  http://10.1.88.114:8001/v1/*
仅使用 Python 标准库，无需安装任何依赖。
"""
import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "http://10.1.88.114:8001"
HOST = "127.0.0.1"
PORT = 8000
UPSTREAM_TIMEOUT = 600  # 长文本生成可能耗时较久


class Handler(BaseHTTPRequestHandler):
    server_version = "QwenChatProxy/1.0"

    def log_message(self, fmt, *args):
        pass  # 保持控制台干净

    # ---------- 工具 ----------
    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _serve_index(self):
        try:
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html"), "rb") as f:
                body = f.read()
        except OSError:
            self._send_json(500, {"error": "index.html 不存在，请确认文件与本脚本在同一目录"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---------- 路由 ----------
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        print("[请求] GET %s" % path, file=sys.stderr)
        if path in ("/", "/index.html"):
            self._serve_index()
        elif path.startswith("/v1/"):
            self._proxy("GET")
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        print("[请求] POST %s" % self.path.split("?", 1)[0], file=sys.stderr)
        if self.path.split("?", 1)[0].startswith("/v1/"):
            self._proxy("POST")
        else:
            self._send_json(404, {"error": "not found"})

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------- 代理 ----------
    def _proxy(self, method):
        url = UPSTREAM + self.path
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(url, data=body, method=method)
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            resp = urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            print("[代理错误] 上游返回 HTTP %d: %s" % (e.code, detail[:500]), file=sys.stderr)
            self._send_json(e.code, {"error": "upstream_error", "detail": detail})
            return
        except Exception as e:
            print("[代理错误] 无法连接上游 API: %s" % e, file=sys.stderr)
            self._send_json(502, {
                "error": "无法连接到 API 服务器 10.1.88.114:8001",
                "detail": str(e),
                "hint": "请确认：1) 已连接校园网；2) 该推理服务在线",
            })
            return

        # 流式透传（HTTP/1.0，响应随连接关闭而结束）
        self.send_response(resp.status)
        self.send_header("Content-Type", resp.headers.get("Content-Type", "application/json"))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            while True:
                chunk = resp.read(4096)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # 用户点击了“停止”或关闭了页面
        finally:
            resp.close()


def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    print("=" * 52)
    print("  Qwen 校园内网聊天服务已启动")
    print("  浏览器打开:  http://localhost:%d" % PORT)
    print("  代理目标:    %s" % UPSTREAM)
    print("  关闭窗口即停止服务")
    print("=" * 52)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
