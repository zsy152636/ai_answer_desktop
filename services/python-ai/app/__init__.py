"""Python AI 服务（FastAPI）包根。

职责：模型适配（provider）、安全校验（url_guard/redaction/auth）、
契约化 HTTP 接口（/health、/api/v1/ai/*）。
"""

__version__ = "1.0.0"
SERVICE_NAME = "python-ai"

__all__ = ["__version__", "SERVICE_NAME"]
