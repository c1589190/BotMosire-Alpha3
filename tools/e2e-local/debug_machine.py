#!/usr/bin/env python3
"""Run and exercise an isolated BMA3 instance over its authenticated HTTP debug API."""

import argparse
import http.client
import json
import os
from pathlib import Path
import stat
import sys
from urllib.parse import quote
from uuid import uuid4


REPO = Path(__file__).resolve().parents[2]
HOST = "127.0.0.1"
INBOX_ID = "bma3-debug"
MAX_PENDING = 32


def config_for(state, mcp_port, inbox_port, debug_port):
    ports = (mcp_port, inbox_port, debug_port)
    if len(set(ports)) != 3 or any(not 1 <= port <= 65535 for port in ports):
        raise ValueError("MCP、Inbox 和 debug 需要三个不同的端口（1–65535）")
    return {
        "dataDir": str(state / "data"),
        "logging": {"dir": str(state / "logs")},
        "security": {"auth": "token"},
        "server": {"host": HOST, "port": mcp_port, "healthPort": debug_port},
        "inbox": {"inboxId": INBOX_ID, "httpPort": inbox_port},
        "qqconnect": {"accounts": []},
        "qqofficial": {"accounts": []},
        "debug": {"enabled": True, "maxPending": MAX_PENDING},
        "weighting": {"importantDelaySeconds": 0},
        "deployer": {"enabled": False},
        "schedule": {"enabled": False},
        "xvfb": {"enabled": False},
    }


def private_path(path, expected_type):
    info = path.lstat()
    if not expected_type(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError(f"需要当前用户专用的文件或目录：{path}")


def serve(args, state, config_file):
    jar = args.jar.resolve(strict=True)
    config = config_for(state, args.mcp_port, args.inbox_port, args.debug_port)
    config_file.parent.mkdir(mode=0o700, exist_ok=True)
    private_path(config_file.parent, stat.S_ISDIR)
    try:
        with config_file.open("x", encoding="utf-8") as stream:
            json.dump(config, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError:
        private_path(config_file, stat.S_ISREG)
        if json.loads(config_file.read_text(encoding="utf-8")) != config:
            raise ValueError("调试机已有不同配置，请使用原端口或新目录") from None
    os.chdir(state)
    os.execvp("java", ["java", "-jar", str(jar), "--config", str(state)])


class DebugClient:
    def __init__(self, state, config_file):
        private_path(config_file.parent, stat.S_ISDIR)
        private_path(config_file, stat.S_ISREG)
        config = json.loads(config_file.read_text(encoding="utf-8"))
        if config["server"]["host"] != HOST or config["inbox"]["inboxId"] != INBOX_ID:
            raise ValueError("配置与本机调试机不匹配")
        self.port = config["server"]["healthPort"]
        token_file = state / ".bma3env"
        private_path(token_file, stat.S_ISREG)
        for line in token_file.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            value = value.strip()
            if key.strip() == "DEBUG_TOKEN" and value.isascii() and value.isalnum():
                self.token = value
                return
        raise ValueError("调试机尚未生成 DEBUG_TOKEN")

    def request(self, method, path, payload=None):
        connection = http.client.HTTPConnection(HOST, self.port, timeout=10)
        try:
            headers = {"Authorization": f"Bearer {self.token}"}
            body = None
            if payload is not None:
                headers["Content-Type"] = "application/json"
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            if not 200 <= response.status < 300:
                raise ValueError(f"{method} {path} 返回 HTTP {response.status}")
            return json.load(response)
        finally:
            connection.close()

    def push(self, text):
        request = self.request("POST", "/debug/ask", {"question": text, "wait": False})
        print(json.dumps({key: request[key] for key in ("id", "status", "injectedSource")}),
              flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    server = commands.add_parser("serve", help="前台启动独立的 BMA3 调试机")
    server.add_argument("--jar", type=Path, required=True)
    server.add_argument("--mcp-port", type=int, default=19865)
    server.add_argument("--inbox-port", type=int, default=19866)
    server.add_argument("--debug-port", type=int, default=19867)
    commands.add_parser("snapshot", help="查看 HTTP debug 快照")
    commands.add_parser("push", help="推送一条调试提问").add_argument("text")
    commands.add_parser("generate", help="生成并推送带编号的合成调试提问").add_argument(
        "count", type=int)
    commands.add_parser("get", help="查询单条提问和回答").add_argument("request_id")
    answer = commands.add_parser("answer", help="用 HTTP 回答一条调试提问")
    answer.add_argument("request_id")
    answer.add_argument("text")
    args = parser.parse_args()

    os.umask(0o077)
    state = args.state_dir.expanduser().resolve()
    if state.is_relative_to(REPO):
        raise ValueError("调试数据和凭据目录必须位于仓库外")
    if args.command == "serve":
        state.mkdir(mode=0o700, parents=True, exist_ok=True)
    private_path(state, stat.S_ISDIR)
    config_file = state / "config" / "app.yaml"
    if args.command == "serve":
        serve(args, state, config_file)
        return

    client = DebugClient(state, config_file)
    if args.command == "push":
        client.push(args.text)
        return
    if args.command == "generate":
        if not 1 <= args.count <= MAX_PENDING:
            raise ValueError(f"一次可生成 1–{MAX_PENDING} 条调试提问")
        batch = uuid4().hex[:8]
        for index in range(1, args.count + 1):
            client.push(f"合成调试提问 {batch} {index}/{args.count}，请回复编号 {index}。")
        return
    if args.command == "get":
        result = client.request("GET", "/debug/ask/" + quote(args.request_id, safe=""))
    elif args.command == "answer":
        result = client.request("POST", "/debug/answer",
                                {"requestId": args.request_id, "text": args.text})
    else:
        result = client.request("GET", "/debug/snapshot")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, http.client.HTTPException) as failure:
        sys.exit(str(failure))
