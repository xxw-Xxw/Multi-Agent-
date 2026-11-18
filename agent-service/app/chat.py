import json
import logging
import operator
import re
from functools import lru_cache
from typing import Annotated, Any, TypedDict
from urllib.parse import quote as url_quote

import httpx
from langgraph.graph import END, START, StateGraph
from pydantic import ValidationError

from .config import get_settings
from .llm import complete_json
from .schemas import Block, ChatRequest, Citation, Finding, RiskLevel
from .sse import error_event, sse_event

MAX_TOOL_CALLS = 3
SEARCH_LIMIT = 8
EXCERPT_CHARS = 300
HISTORY_MESSAGES = 8
STREAM_CHUNK_CHARS = 12
QUERY_MAX_CHARS = 80
FOLLOW_UP_MAX_CHARS = 14
MAX_CITATION_BLOCKS = 3
logger = logging.getLogger(__name__)


class ChatState(TypedDict, total=False):
    request: ChatRequest
    rounds: int
    next_tool: dict[str, Any] | None
    calls: list[dict[str, Any]]
    tool_events: Annotated[list[dict[str, Any]], operator.add]
    tool_errors: Annotated[list[str], operator.add]
    blocks: dict[str, Block]
    findings: list[Finding]
    answer: str
    citations: list[dict[str, str]]
    usage_tokens: Annotated[int, operator.add]


def _is_risk_summary(request: ChatRequest) -> bool:
    message = request.message
    return "风险" in message and any(
        phrase in message for phrase in ["总结", "汇总", "整体", "哪些", "风险点", "风险情况", "全部"]
    )


def _block_id_from(text: str) -> str | None:
    explicit = re.search(r"(?:blockId|block|块)\s*[:：#]?\s*([A-Za-z0-9_-]+)", text, re.IGNORECASE)
    if explicit:
        return explicit.group(1)
    match = re.search(r"\bb[_-][A-Za-z0-9_-]+\b", text, re.IGNORECASE)
    return match.group(0) if match else None


def _requested_block_id(request: ChatRequest) -> str | None:
    block_id = _block_id_from(request.message)
    if block_id:
        return block_id
    if "原文" in request.message or "上述" in request.message or "这条" in request.message:
        for item in reversed(request.history):
            block_id = _block_id_from(item.content)
            if block_id:
                return block_id
    return None


def _is_follow_up(message: str) -> bool:
    """判断是否为承接上一轮的简短追问，例如「为什么」「依据是什么」「详细说说」。"""
    text = message.strip()
    if len(text) > FOLLOW_UP_MAX_CHARS:
        return False
    return any(
        word in text
        for word in ["这条", "该条款", "上述", "刚才", "原文", "为什么", "依据", "理由", "原因", "详细", "展开", "继续说", "还有", "怎么改", "如何"]
    )


QUERY_CUT_MARKERS = ["是怎么", "是什么", "是多少", "有什么", "有哪些", "怎么", "如何", "为什么", "是否", "能否", "多少"]


def _clean_query(source: str) -> str:
    """把自然语言问题压成检索短语，例如「违约金是怎么约定的」→「违约金」。"""
    cleaned = re.sub(r"\s+", " ", source).strip()
    for phrase in ["请帮我", "请", "查一下", "查询", "原文", "怎么说的", "怎么说", "是什么", "相关条款"]:
        cleaned = cleaned.replace(phrase, "")
    for marker in QUERY_CUT_MARKERS:
        index = cleaned.find(marker)
        if index > 0:
            cleaned = cleaned[:index]
            break
    return cleaned.strip(" ，。！？!?；;：:的")


def _search_query(request: ChatRequest) -> str:
    """检索词由当前问题决定；追问或指代上一轮时回退到最近一条用户消息。

    领域关键词只保留在 Java 的检索打分里，避免两套词表各自漂移。
    """
    source = request.message.strip()
    if not _is_follow_up(source):
        cleaned = _clean_query(source)
        if cleaned:
            return cleaned[:QUERY_MAX_CHARS]
    for item in reversed(request.history):
        if item.role == "user" and item.content.strip():
            return _clean_query(item.content)[:QUERY_MAX_CHARS] or item.content.strip()[:QUERY_MAX_CHARS]
    return _clean_query(source)[:QUERY_MAX_CHARS] or source[:QUERY_MAX_CHARS]


def _calls_with(calls: list[dict[str, Any]], tool: str) -> list[dict[str, Any]]:
    return [call for call in calls if call.get("tool") == tool]


def _choose_tool(state: ChatState) -> dict[str, Any] | None:
    request = state["request"]
    calls = state.get("calls", [])
    if len(calls) >= MAX_TOOL_CALLS:
        return None
    blocks = state.get("blocks", {})
    findings = state.get("findings", [])

    block_id = _requested_block_id(request)
    if block_id:
        if not _calls_with(calls, "get_block"):
            return {"tool": "get_block", "args": {"blockId": block_id}}
        return None

    if _is_risk_summary(request):
        if not _calls_with(calls, "get_findings"):
            return {"tool": "get_findings", "args": {"taskId": request.taskId}}
        attempted = {
            call.get("args", {}).get("blockId") for call in _calls_with(calls, "get_block")
        }
        ordered = sorted(findings, key=lambda item: -item.level.weight)
        for finding in ordered:
            if finding.blockId not in blocks and finding.blockId not in attempted:
                return {"tool": "get_block", "args": {"blockId": finding.blockId}}
        return None

    if not _calls_with(calls, "search_blocks"):
        return {
            "tool": "search_blocks",
            "args": {"q": _search_query(request), "limit": SEARCH_LIMIT},
        }
    return None


async def decide_tool_node(state: ChatState) -> dict[str, Any]:
    tool = _choose_tool(state)
    events = []
    if tool:
        events.append(
            {
                "messageId": state["request"].messageId,
                "tool": tool["tool"],
                "args": tool["args"],
            }
        )
    return {"next_tool": tool, "tool_events": events}


def route_tool(state: ChatState) -> str:
    return "execute_tool" if state.get("next_tool") else "answer"


async def execute_tool_node(state: ChatState) -> dict[str, Any]:
    request = state["request"]
    tool_call = state["next_tool"]
    assert tool_call is not None
    tool = tool_call["tool"]
    args = tool_call["args"]
    blocks = dict(state.get("blocks", {}))
    findings = state.get("findings", [])
    errors: list[str] = []
    base_url = request.javaBaseUrl.rstrip("/")
    document_id = url_quote(request.documentId, safe="")
    task_id = url_quote(request.taskId, safe="")

    try:
        timeout = httpx.Timeout(get_settings().timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout) as client:
            if tool == "search_blocks":
                response = await client.get(
                    f"{base_url}/internal/docs/{document_id}/blocks/search",
                    params={"q": args["q"], "limit": args.get("limit", SEARCH_LIMIT)},
                )
                response.raise_for_status()
                data = response.json()
                if not isinstance(data, list):
                    raise ValueError("search_blocks 返回值不是 Block 数组")
                for item in data[:SEARCH_LIMIT]:
                    block = Block.model_validate(item)
                    blocks[block.id] = block
            elif tool == "get_findings":
                response = await client.get(f"{base_url}/internal/tasks/{task_id}/findings")
                response.raise_for_status()
                data = response.json()
                if not isinstance(data, list):
                    raise ValueError("get_findings 返回值不是 Finding 数组")
                findings = [Finding.model_validate(item) for item in data]
            elif tool == "get_block":
                block_id = url_quote(str(args["blockId"]), safe="")
                response = await client.get(
                    f"{base_url}/internal/docs/{document_id}/blocks/{block_id}"
                )
                response.raise_for_status()
                block = Block.model_validate(response.json())
                blocks[block.id] = block
            else:
                raise ValueError(f"未知工具：{tool}")
    except Exception as exc:
        errors.append(f"{tool} 调用失败：{exc}")

    return {
        "rounds": len(state.get("calls", [])) + 1,
        "calls": [*state.get("calls", []), tool_call],
        "blocks": blocks,
        "findings": findings,
        "tool_errors": errors,
    }


def _excerpt(block: Block, query: str) -> str:
    text = block.text
    if query:
        for sentence in re.split(r"(?<=[。；;！？\n])", text):
            trimmed = sentence.strip()
            if trimmed and query in trimmed:
                return trimmed[:EXCERPT_CHARS]
    stripped = text.strip()
    return stripped[:EXCERPT_CHARS]


def _validate_citations(
    candidates: list[Citation | dict[str, Any]], blocks: dict[str, Block]
) -> list[Citation]:
    valid: list[Citation] = []
    seen: set[tuple[str, str]] = set()
    for candidate in candidates:
        try:
            citation = candidate if isinstance(candidate, Citation) else Citation.model_validate(candidate)
        except ValidationError:
            continue
        block = blocks.get(citation.blockId)
        if block is None or not citation.quote or citation.quote not in block.text:
            continue
        key = (citation.blockId, citation.quote)
        if key not in seen:
            seen.add(key)
            valid.append(citation)
    return valid


def _mock_answer(state: ChatState) -> tuple[str, list[Citation]]:
    request = state["request"]
    blocks = state.get("blocks", {})
    findings = state.get("findings", [])
    errors = state.get("tool_errors", [])

    if _is_risk_summary(request):
        if not findings:
            suffix = "；同时检索接口出现错误" if errors else ""
            return (
                f"未找到已落库的审查风险结论{suffix}，因此没有可核实的风险总结依据。可以先运行文档审查，或改问具体条款。",
                [],
            )
        verified = [
            (finding, blocks[finding.blockId])
            for finding in sorted(findings, key=lambda item: -item.level.weight)
            if finding.blockId in blocks
            and finding.quote
            and finding.quote in blocks[finding.blockId].text
        ]
        if not verified:
            return "已获取审查结论，但未找到可逐字核实的原文依据，因此不生成具体风险判断。", []
        high = sum(finding.level == RiskLevel.HIGH for finding in findings)
        medium = sum(finding.level == RiskLevel.MEDIUM for finding in findings)
        low = sum(finding.level == RiskLevel.LOW for finding in findings)
        lines = [
            f"根据审查结论，共记录 {len(findings)} 条风险：高危 {high} 条、中危 {medium} 条、低危 {low} 条。以下为已核对原文的重点："
        ]
        citations: list[Citation] = []
        level_names = {RiskLevel.HIGH: "高危", RiskLevel.MEDIUM: "中危", RiskLevel.LOW: "低危"}
        for index, (finding, block) in enumerate(verified[:MAX_CITATION_BLOCKS], start=1):
            reason = finding.reason or finding.ruleId or "需关注该条款"
            lines.append(
                f"{index}. 【{level_names[finding.level]}】{reason} 原文：“{finding.quote}”（{block.id}，第 {block.page} 页）。"
            )
            if finding.suggestion:
                lines.append(f"   建议：{finding.suggestion}")
            citations.append(Citation(blockId=block.id, quote=finding.quote))
        return "\n".join(lines), citations

    if not blocks:
        suffix = "（Java 检索接口暂不可用）" if errors else ""
        return f"未找到与问题相关的文档原文依据{suffix}，不能据此作答。请补充更具体的条款名称或关键词。", []

    query = _search_query(request)
    heading = "相关原文如下：" if "原文" in request.message else f"关于“{query}”，检索到以下原文依据："
    lines = [heading]
    citations = []
    for index, block in enumerate(list(blocks.values())[:MAX_CITATION_BLOCKS], start=1):
        excerpt = _excerpt(block, query)
        if not excerpt:
            continue
        lines.append(f"{index}. {excerpt}（{block.id}，第 {block.page} 页）")
        citations.append(Citation(blockId=block.id, quote=excerpt))
    if not citations:
        return "未找到可用于回答的文档原文依据，不能据此作答。", []
    if query == "违约金" and "原文" not in request.message:
        lines.append("以上仅为原文定位；违约金是否适当，还需结合计算基数、实际损失及约定上限进一步判断。")
    return "\n".join(lines), citations


def _history_text(request: ChatRequest) -> str:
    return "\n".join(f"{item.role}: {item.content}" for item in request.history[-HISTORY_MESSAGES:])


def _chat_prompt(state: ChatState) -> tuple[str, str]:
    request = state["request"]
    system = (
        "你是单文档问答 ChatAgent。只能根据给定原文与审查结论回答，不得编造。"
        "没有依据时必须明确说未找到依据。只输出 JSON："
        '{"answer":"中文答案","citations":[{"blockId":"原文块ID","quote":"逐字原文"}]}。'
        "每个 citation.quote 必须是对应原文块的逐字子串。"
    )
    lines = [f"当前问题：{request.message}", f"对话历史：\n{_history_text(request)}", "【已检索原文】"]
    for block in state.get("blocks", {}).values():
        lines.extend(
            [
                f"===BLOCK_START=== blockId={block.id} page={block.page} section={block.sectionPath}",
                block.text,
                "===BLOCK_END===",
            ]
        )
    if state.get("findings"):
        lines.append("【审查结论】")
        lines.append(
            json.dumps(
                [finding.model_dump(mode="json") for finding in state["findings"]],
                ensure_ascii=False,
            )
        )
    return system, "\n".join(lines)


async def answer_node(state: ChatState) -> dict[str, Any]:
    settings = get_settings()
    tokens = 0
    if settings.effective_provider == "openai" and state.get("blocks"):
        system, prompt = _chat_prompt(state)
        for attempt in range(2):
            try:
                data, _, used = await complete_json(system, prompt, settings)
                tokens += used
                answer = data.get("answer")
                raw_citations = data.get("citations", [])
                if not isinstance(answer, str) or not answer.strip() or not isinstance(raw_citations, list):
                    raise ValueError("ChatAgent 未返回有效的 answer/citations")
                citations = _validate_citations(raw_citations, state.get("blocks", {}))
                if not citations:
                    raise ValueError("ChatAgent 没有返回通过原文校验的引用")
                answer = answer.strip()
                break
            except Exception as exc:
                if attempt == 0:
                    logger.info("ChatAgent 首次回答未通过校验，追加约束后重试：%s", exc)
                    prompt += (
                        "\n\n上一次回答的 citations 未通过逐字校验。"
                        "请只引用 ===BLOCK_START=== 行中的 blockId，并让 quote 从该块原文中精确复制（含标点）。"
                    )
                    continue
                logger.warning("ChatAgent 模型回答未通过校验，降级为规则回答：%s", exc)
                answer, citations = _mock_answer(state)
    else:
        answer, citations = _mock_answer(state)
    citations = _validate_citations(citations, state.get("blocks", {}))
    return {
        "answer": answer,
        "citations": [citation.model_dump() for citation in citations],
        "usage_tokens": tokens,
    }


@lru_cache(maxsize=1)
def get_chat_graph():
    builder = StateGraph(ChatState)
    builder.add_node("decide_tool", decide_tool_node)
    builder.add_node("execute_tool", execute_tool_node)
    builder.add_node("answer", answer_node)
    builder.add_edge(START, "decide_tool")
    builder.add_conditional_edges("decide_tool", route_tool, {"execute_tool": "execute_tool", "answer": "answer"})
    builder.add_edge("execute_tool", "decide_tool")
    builder.add_edge("answer", END)
    return builder.compile()


def _delta_chunks(answer: str, size: int = STREAM_CHUNK_CHARS):
    for start in range(0, len(answer), size):
        yield answer[start:start + size]


async def chat_event_stream(request: ChatRequest):
    total_tokens = 0
    try:
        initial: ChatState = {"request": request, "rounds": 0, "calls": [], "blocks": {}, "findings": []}
        async for updates in get_chat_graph().astream(initial, stream_mode="updates"):
            for node, update in updates.items():
                if not isinstance(update, dict):
                    continue
                total_tokens += int(update.get("usage_tokens", 0))
                for event in update.get("tool_events", []):
                    yield sse_event("tool_call", event)
                if node == "answer":
                    for delta in _delta_chunks(update["answer"]):
                        yield sse_event(
                            "token",
                            {"messageId": request.messageId, "agent": "ChatAgent", "delta": delta},
                        )
                    yield sse_event(
                        "done",
                        {
                            "messageId": request.messageId,
                            "citations": update.get("citations", []),
                            "usage": {"tokens": total_tokens},
                        },
                    )
    except Exception as exc:
        yield error_event(request.messageId, f"对话流水线执行失败：{exc}", retryable=True)
