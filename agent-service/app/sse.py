import json
from typing import Any


def sse_event(event: str, data: dict[str, Any]) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {payload}\n\n"


def error_event(message_id: str, message: str, retryable: bool = True) -> str:
    return sse_event(
        "error",
        {"messageId": message_id, "message": message, "retryable": retryable},
    )
