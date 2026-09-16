"""로컬 검증용 기록 서버 — Slack Web API와 n8n 웹훅 자리에 세워 받은 요청을 JSON 줄로 남긴다.

실 워크스페이스·n8n 인스턴스 없이 관통을 재현하려고 둔다. 이 서버가 받은 것은 "실행면이 보내려 한 것"까지이고,
Slack이 실제로 표시했는지는 검증하지 않는다(VERIFICATION 169절에 같은 한계를 적는다).

    python tools/record_server.py 18099 /tmp/recorded.jsonl
"""
from __future__ import annotations

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_PASSED_HEADERS = ("Authorization", "X-DBTower-Timestamp", "X-DBTower-Signature", "Content-Type")


def main() -> None:
    port, out = int(sys.argv[1]), sys.argv[2]

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802 - http.server 규약
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            try:
                body = json.loads(raw)
            except ValueError:
                body = raw.decode(errors="replace")
            headers = {h: self.headers[h] for h in _PASSED_HEADERS if self.headers.get(h)}
            if "Authorization" in headers:
                headers["Authorization"] = headers["Authorization"][:12] + "..."  # 토큰을 파일에 남기지 않는다
            record = {"at": time.time(), "path": self.path, "headers": headers, "body": body, "raw_len": len(raw)}
            with open(out, "a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
            reply = json.dumps({"ok": True, "ts": f"{time.time():.6f}"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        def log_message(self, *args) -> None:
            pass

    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
