"""真实验证脚本：启动本服务（随机端口）→ 调用真实 Ollama → 校验中文与结构。

区别于 smoke_ollama.ps1：本脚本用 Python 直接解析 JSON，避免 PowerShell 5.1
在处理 HTTP 响应体时二次转码造成中文乱码，因此可以看到响应的真实文本。
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

SERVICE_DIR = Path(__file__).resolve().parent.parent
VENV_PYTHON = SERVICE_DIR / ".venv" / "Scripts" / "python.exe"

PROFILE = {
    "id": "ollama-local",
    "provider": "openai-compatible",
    "baseUrl": "http://127.0.0.1:11434/v1",
    "model": "qwen3:8b",
    "timeoutSeconds": 180,
    "connectTimeoutSeconds": 5,
    "allowLocalNetwork": True,
    "parameters": {"temperature": 0.2, "maxTokens": 1024},
    "apiKey": "local-ollama",
}


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


async def main() -> int:
    port = free_port()
    endpoint_file = Path(tempfile.gettempdir()) / "py-ai-e2e-endpoint.json"
    endpoint_file.unlink(missing_ok=True)

    log_path = SERVICE_DIR / "e2e-stdout.log"
    err_path = SERVICE_DIR / "e2e-stderr.log"
    with log_path.open("wb") as out, err_path.open("wb") as err:
        proc = subprocess.Popen(
            [
                str(VENV_PYTHON),
                "-m",
                "app.main",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--endpoint-file",
                str(endpoint_file),
                "--log-level",
                "INFO",
            ],
            cwd=str(SERVICE_DIR),
            stdout=out,
            stderr=err,
        )
        try:
            deadline = time.monotonic() + 30
            while not endpoint_file.exists():
                if time.monotonic() > deadline:
                    print("FAIL: endpoint 文件未生成")
                    return 1
                if proc.poll() is not None:
                    print("FAIL: 进程提前退出", proc.returncode)
                    return 1
                await asyncio.sleep(0.1)
            meta = json.loads(endpoint_file.read_text(encoding="utf-8"))
            base = f"http://127.0.0.1:{port}"
            headers = {"X-Internal-Token": meta["token"]}
            print(f"[1] endpoint: port={meta['port']} pid={meta['pid']} startedAt={meta['startedAt']}")

            async with httpx.AsyncClient(timeout=300.0) as client:
                health = (await client.get(f"{base}/health")).json()
                print(f"[2] /health -> {json.dumps(health, ensure_ascii=False)}")
                assert health == {
                    "status": "UP",
                    "service": "python-ai",
                    "version": "1.0.0",
                    "providers": ["openai-compatible", "ollama"],
                }

                no_token = await client.post(f"{base}/api/v1/ai/explain", json={})
                print(f"[3] 无令牌 -> HTTP {no_token.status_code} {json.dumps(no_token.json(), ensure_ascii=False)[:160]}")

                tc = await client.post(
                    f"{base}/api/v1/ai/test-connection",
                    json={"profile": PROFILE},
                    headers=headers,
                )
                print(f"[4] test-connection -> {json.dumps(tc.json(), ensure_ascii=False)}")

                probe = await client.get(
                    f"{base}/api/v1/ai/profiles/ollama-local/probe",
                    params={"baseUrl": PROFILE["baseUrl"], "allowLocalNetwork": "true"},
                    headers=headers,
                )
                print(f"[5] probe -> {json.dumps(probe.json(), ensure_ascii=False)}")

                explain = await client.post(
                    f"{base}/api/v1/ai/explain",
                    json={
                        "question": {
                            "stem": "TCP 三次握手过程中，客户端第一次发送的报文段中 SYN 标志位与序号字段的作用分别是什么？",
                            "questionType": "single_choice",
                            "options": [
                                {"key": "A", "content": "请求建立连接并同步初始序号"},
                                {"key": "B", "content": "确认收到对方数据并同步序号"},
                                {"key": "C", "content": "结束连接并释放资源"},
                            ],
                            "answer": {"type": "single_choice", "value": "A"},
                            "explanation": "SYN 用于请求建立连接，同时携带客户端初始序号 ISN。",
                        },
                        "userAnswer": {"type": "single_choice", "value": "B"},
                        "profile": PROFILE,
                        "locale": "zh-CN",
                    },
                    headers=headers,
                )
                explain_body = explain.json()
                print(f"[6] explain HTTP {explain.status_code} success={explain_body.get('success')}")
                if explain_body.get("success"):
                    data = explain_body["data"]
                    print(f"    model={data['model']} latencyMs={data['latencyMs']} usage={json.dumps(data['usage'], ensure_ascii=False)}")
                    print(f"    profileRef={data['profileRef']} tipsCount={len(data['tips'])}")
                    print(f"    explanation[:200]={data['explanation'][:200]}")
                    print(f"    tips={json.dumps(data['tips'], ensure_ascii=False)}")
                    assert data["explanation"].strip(), "讲解不能为空"
                    assert data["profileRef"] == "profile://ollama-local", (
                        f"profileRef 必须是 profile://<profileId>，实际 {data['profileRef']!r}"
                    )
                else:
                    print(f"    error={json.dumps(explain_body['error'], ensure_ascii=False)}")

                grade = await client.post(
                    f"{base}/api/v1/ai/grade-subjective",
                    json={
                        "question": {
                            "stem": "请说明 TCP 三次握手的过程与目的。",
                            "referenceAnswer": "客户端发送 SYN，服务端回应 SYN/ACK，客户端再发送 ACK；目的是同步双方初始序号并确认收发能力。",
                            "rubric": {
                                "criteria": [
                                    {"id": "c1", "desc": "指出三次握手顺序", "weight": 60},
                                    {"id": "c2", "desc": "说明握手目的", "weight": 40},
                                ],
                                "maxScore": 10,
                            },
                        },
                        "userAnswer": "客户端先发 SYN，服务端回 SYN/ACK，客户端再发 ACK，用来确认双方都能收发数据。",
                        "profile": PROFILE,
                        "maxScore": 10,
                    },
                    headers=headers,
                )
                grade_body = grade.json()
                print(f"[7] grade-subjective HTTP {grade.status_code} success={grade_body.get('success')}")
                if grade_body.get("success"):
                    data = grade_body["data"]
                    print(f"    suggestedScore={data['suggestedScore']}/{data['maxScore']} confidence={data['confidence']} needsReview={data['needsReview']} model={data['model']} latencyMs={data['latencyMs']}")
                    print(f"    criteria={json.dumps(data['criteria'], ensure_ascii=False)}")
                    assert 0 <= data["suggestedScore"] <= data["maxScore"]
                    assert data["needsReview"] is True
                else:
                    print(f"    error={json.dumps(grade_body['error'], ensure_ascii=False)}")

            print("[8] 日志检查")
            text = log_path.read_text(encoding="utf-8", errors="replace")
            leaks = {
                "完整内部令牌": meta["token"] in text,
                "apiKey 明文": "local-ollama" in text,
            }
            print(f"    泄漏检查: {json.dumps(leaks, ensure_ascii=False)}")
            assert not any(leaks.values())
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
            endpoint_file.unlink(missing_ok=True)
            await asyncio.sleep(0.5)
            with socket.socket() as sock:
                in_use = sock.connect_ex(("127.0.0.1", port)) == 0
            print(f"[9] 进程已停止 returncode={proc.returncode}，端口 {port} 仍被监听={in_use}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
