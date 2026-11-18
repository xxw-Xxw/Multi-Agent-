import json
import re
from typing import Any

from .config import Settings


def estimate_tokens(*parts: object) -> int:
    length = sum(len(str(part)) for part in parts)
    return max(1, (length + 3) // 4) if length else 0


def extract_json_object(raw: str) -> dict[str, Any]:
    text = raw.strip()
    fence = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", text, re.DOTALL | re.IGNORECASE)
    if fence:
        text = fence.group(1).strip()
    try:
        value = json.loads(text)
        if isinstance(value, dict):
            return value
    except json.JSONDecodeError:
        pass

    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char != "{":
            continue
        try:
            value, _ = decoder.raw_decode(text[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    raise ValueError("模型输出中没有有效的 JSON 对象")


def _content_text(content: object) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts)
    return str(content)


async def complete_json(
    system: str, user: str, settings: Settings
) -> tuple[dict[str, Any], str, int]:
    if settings.effective_provider != "openai":
        raise RuntimeError("complete_json 仅用于 openai 兼容提供商")

    from langchain_openai import ChatOpenAI

    llm = ChatOpenAI(
        model=settings.model,
        api_key=settings.api_key,
        base_url=settings.base_url,
        timeout=settings.timeout_seconds,
        max_retries=1,
    )
    response = await llm.ainvoke([("system", system), ("human", user)])
    raw = _content_text(response.content)
    parsed = extract_json_object(raw)
    usage = getattr(response, "usage_metadata", None) or {}
    tokens = int(usage.get("input_tokens", 0)) + int(usage.get("output_tokens", 0))
    if tokens == 0:
        tokens = estimate_tokens(system, user, raw)
    return parsed, raw, tokens
