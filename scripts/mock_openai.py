"""
本地 mock OpenAI 兼容端点：截获 AgentScope Java 发出的真实请求体并打印。

用途：不花钱、不依赖外网，就能看清「我们的代码到底往 MiniMax 发了什么」：
  - model 字段是什么
  - system role 有没有被 MiniMaxFormatter 保住（通用 OpenAI Formatter 会丢，框架 #2189）
  - 长度参数是 max_tokens 还是 max_completion_tokens
  - tools 的 JSON Schema 长什么样

用法：
    python mock_openai.py 9999
然后把 demo 的 base-url 指到 http://localhost:9999/v1
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9999

# 回显请求里出现的手机号 —— 用来真机演示「出站脱敏」。
# 没有它，mock 只能回固定文案，脱敏中间件在真机上永远没东西可脱。
MOBILE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")


def sse_chunk(delta, finish=None, usage=None):
    payload = {
        "id": "chatcmpl-mock",
        "object": "chat.completion.chunk",
        "created": 1764000000,
        "model": "mock",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    if usage:
        payload["usage"] = usage
    return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")

        print("=" * 78)
        print("POST", self.path)
        print("--- Authorization 头 ---")
        auth = self.headers.get("Authorization", "<缺失>")
        print("  ", auth[:14] + "..." if len(auth) > 14 else auth)
        print("--- 请求体（格式化）---")
        try:
            body = json.loads(raw)
            print(json.dumps(body, ensure_ascii=False, indent=2))
            phones = MOBILE.findall(json.dumps(body, ensure_ascii=False))
        except Exception:
            print(raw)
            phones = []
        print("=" * 78, flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        first = "【MOCK】已收到请求"
        if phones:
            first += "，回显您的手机号 " + phones[0]
        out = (
            sse_chunk({"role": "assistant", "content": first})
            + sse_chunk({"content": "，这是本地 mock 回复。"}, usage={
                "prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18})
            + sse_chunk({}, finish="stop")
            + "data: [DONE]\n\n"
        )
        self.wfile.write(out.encode("utf-8"))

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"mock OpenAI 端点已启动：http://localhost:{PORT}/v1", flush=True)
    HTTPServer(("localhost", PORT), Handler).serve_forever()
