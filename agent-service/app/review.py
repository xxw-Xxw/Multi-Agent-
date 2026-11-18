import json
import operator
import re
import time
from functools import lru_cache
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from .config import get_settings
from .llm import complete_json, estimate_tokens
from .schemas import Block, Finding, ReviewRequest, ReviewRule, RiskLevel
from .sse import error_event, sse_event

DOMAINS = ["LEGAL", "FINANCE", "COMPLIANCE"]
DOMAIN_NAMES = {"LEGAL": "法务", "FINANCE": "财务", "COMPLIANCE": "合规"}

# 各阶段进度：100% 只能由 Java 在结果落库后发布
PROGRESS_PLANNING = 18
PROGRESS_REVIEWING = 20
PROGRESS_REVIEWING_SPAN = 60
PROGRESS_CRITIQUING = 82
PROGRESS_ARBITRATING = 90
PROGRESS_REPORTING = 96
PROGRESS_SAVING = 98

# Planner 只取文档开头做类型识别，避免把全文塞进提示词
PLANNER_EXCERPT_BLOCKS = 20
PLANNER_EXCERPT_CHARS = 6000


class Segment(TypedDict):
    block: Block
    text: str


class Section(TypedDict):
    id: str
    index: int
    segments: list[Segment]


class ExpertResult(TypedDict):
    domain: str
    sectionId: str
    findings: list[Finding]


class ReviewState(TypedDict, total=False):
    request: ReviewRequest
    docType: str
    sections: list[Section]
    domain: str
    section: Section
    expert_results: Annotated[list[ExpertResult], operator.add]
    traces: Annotated[list[dict[str, Any]], operator.add]
    usage_tokens: Annotated[int, operator.add]
    degraded_reasons: Annotated[list[str], operator.add]
    validated_findings: list[Finding]
    final_findings: list[Finding]
    dropped_count: int
    summary: str
    result: dict[str, Any]
    empty_experts: bool


def plan_sections(blocks: list[Block], max_chars: int) -> list[Section]:
    sections: list[Section] = []
    current: list[Segment] = []
    current_chars = 0

    def flush() -> None:
        nonlocal current, current_chars
        if current:
            sections.append(
                {"id": f"s{len(sections) + 1:04d}", "index": len(sections), "segments": current}
            )
            current = []
            current_chars = 0

    for block in blocks:
        if block.type.upper() == "HEADING":
            continue
        if not block.text:
            current.append({"block": block, "text": ""})
            continue
        remaining = block.text
        while remaining:
            if current_chars >= max_chars:
                flush()
            capacity = max_chars - current_chars
            piece = remaining[:capacity]
            current.append({"block": block, "text": piece})
            current_chars += len(piece)
            remaining = remaining[len(piece):]
            if current_chars >= max_chars:
                flush()
    flush()
    return sections


def _heuristic_doc_type(request: ReviewRequest) -> str:
    text = request.documentName + "\n" + "\n".join(block.text for block in request.blocks[:20])
    if "招标" in text:
        return "招标文件"
    if "管理制度" in text or "管理办法" in text:
        return "管理制度"
    if "合同" in text or "协议" in text:
        return "采购合同"
    return "通用文档"


def _trace(
    task_id: str,
    agent_name: str,
    started: float,
    input_data: dict[str, Any],
    output_data: dict[str, Any],
    tokens: int = 0,
    error: Exception | None = None,
) -> dict[str, Any]:
    trace: dict[str, Any] = {
        "messageId": task_id,
        "agentName": agent_name,
        "status": "FAILED" if error else "SUCCESS",
        "input": input_data,
        "output": output_data,
        "tokens": tokens,
        "latencyMs": max(0, int((time.perf_counter() - started) * 1000)),
    }
    if error:
        trace["error"] = str(error)[:500]
    return trace


async def planner_node(state: ReviewState) -> dict[str, Any]:
    started = time.perf_counter()
    request = state["request"]
    sections = plan_sections(request.blocks, request.sectionMaxChars)
    settings = get_settings()
    tokens = 0
    error: Exception | None = None
    degraded: list[str] = []
    try:
        if settings.effective_provider == "openai":
            system = (
                "你是 PLANNER 文档规划 Agent。根据文档名称与正文判断文档类型，只输出 JSON："
                '{"docType":"采购合同|招标文件|管理制度|通用文档"}。'
            )
            excerpt = "\n".join(
                block.text for block in request.blocks[:PLANNER_EXCERPT_BLOCKS]
            )[:PLANNER_EXCERPT_CHARS]
            data, _, tokens = await complete_json(
                system,
                f"文档名称：{request.documentName}\n正文摘录：\n{excerpt}",
                settings,
            )
            doc_type = data.get("docType")
            if not isinstance(doc_type, str) or not doc_type.strip():
                raise ValueError("Planner 未返回有效的 docType")
            doc_type = doc_type.strip()
        else:
            doc_type = _heuristic_doc_type(request)
            tokens = estimate_tokens(request.documentName, doc_type)
    except Exception as exc:
        error = exc
        doc_type = _heuristic_doc_type(request)
        degraded.append(f"PlannerAgent 已降级为规则规划：{exc}")

    trace = _trace(
        request.taskId,
        "PlannerAgent",
        started,
        {
            "documentName": request.documentName,
            "blockCount": len(request.blocks),
            "sectionMaxChars": request.sectionMaxChars,
        },
        {"docType": doc_type, "sectionCount": len(sections), "experts": DOMAINS},
        tokens,
        error,
    )
    return {
        "docType": doc_type,
        "sections": sections,
        "traces": [trace],
        "usage_tokens": tokens,
        "degraded_reasons": degraded,
    }


def fanout_experts(state: ReviewState) -> list[Send]:
    request = state["request"]
    sends = [
        Send(
            "expert",
            {"request": request, "section": section, "domain": domain, "docType": state.get("docType", "通用文档")},
        )
        for section in state.get("sections", [])
        for domain in DOMAINS
    ]
    return sends or [Send("empty_experts", {})]


def _sentence_of(text: str, keyword: str) -> str:
    for sentence in re.split(r"(?<=[。；;！？\n])", text):
        trimmed = sentence.strip()
        if trimmed and keyword in trimmed:
            return trimmed
    index = text.find(keyword)
    start = max(0, index - 30)
    end = min(len(text), index + len(keyword) + 30)
    return text[start:end].strip()


def _mock_expert(
    domain: str, section: Section, rules: list[ReviewRule]
) -> list[Finding]:
    findings: list[Finding] = []
    for segment in section["segments"]:
        block = segment["block"]
        text = segment["text"]
        for rule in rules:
            keyword = next((item for item in rule.keywords if item and item in text), None)
            if keyword is None:
                continue
            findings.append(
                Finding(
                    agentName=f"{DOMAIN_NAMES[domain]}专家Agent",
                    ruleId=rule.id,
                    level=rule.level,
                    blockId=block.id,
                    page=block.page,
                    quote=_sentence_of(text, keyword),
                    reason=rule.reason,
                    suggestion=rule.suggestion,
                )
            )
    return findings


def _expert_prompt(
    doc_type: str, domain: str, rules: list[ReviewRule], section: Section
) -> tuple[str, str]:
    system = (
        f"你是「{DOMAIN_NAMES[domain]}」审查专家 Agent，标记为 EXPERT:{domain}。"
        "只能依据给定片段和规则，不得编造。quote 必须逐字摘自对应 block 的文本。"
        "没有发现时返回空数组。只输出 JSON："
        '{"findings":[{"ruleId":"规则ID","level":"HIGH|MEDIUM|LOW",'
        '"blockId":"块ID","quote":"原文摘录","reason":"风险理由","suggestion":"修改建议"}]}'
    )
    rule_data = [rule.model_dump(mode="json") for rule in rules]
    lines = [f"【文档类型】{doc_type}", "【审查清单】", json.dumps(rule_data, ensure_ascii=False), "【文档片段】"]
    for segment in section["segments"]:
        block = segment["block"]
        lines.extend(
            [
                f"===BLOCK_START=== blockId={block.id} page={block.page} section={block.sectionPath}",
                segment["text"],
                "===BLOCK_END===",
            ]
        )
    return system, "\n".join(lines)


async def _model_expert(
    request: ReviewRequest,
    doc_type: str,
    domain: str,
    section: Section,
    rules: list[ReviewRule],
) -> tuple[list[Finding], int]:
    settings = get_settings()
    system, prompt = _expert_prompt(doc_type, domain, rules, section)
    data, _, tokens = await complete_json(system, prompt, settings)
    raw_findings = data.get("findings", [])
    if not isinstance(raw_findings, list):
        raise ValueError("专家输出的 findings 不是数组")

    rule_map = {rule.id: rule for rule in rules}
    block_map = {segment["block"].id: segment["block"] for segment in section["segments"]}
    findings: list[Finding] = []
    for item in raw_findings:
        if not isinstance(item, dict):
            continue
        rule = rule_map.get(str(item.get("ruleId", "")))
        block = block_map.get(str(item.get("blockId", "")))
        quote = item.get("quote")
        if rule is None or block is None or not isinstance(quote, str) or not quote:
            continue
        try:
            level = RiskLevel(str(item.get("level", rule.level.value)).upper())
        except ValueError:
            level = rule.level
        findings.append(
            Finding(
                agentName=f"{DOMAIN_NAMES[domain]}专家Agent",
                ruleId=rule.id,
                level=level,
                blockId=block.id,
                page=block.page,
                quote=quote,
                reason=str(item.get("reason") or rule.reason),
                suggestion=str(item.get("suggestion") or rule.suggestion),
            )
        )
    return findings, tokens


async def expert_node(state: ReviewState) -> dict[str, Any]:
    started = time.perf_counter()
    request = state["request"]
    section = state["section"]
    domain = state["domain"]
    agent_name = f"{DOMAIN_NAMES[domain]}专家Agent#{section['id']}"
    rules = [rule for rule in request.rules if rule.domain.upper() == domain]
    error: Exception | None = None
    degraded: list[str] = []
    used_fallback = False
    tokens = 0
    try:
        if not rules or not section["segments"]:
            findings = []
        elif get_settings().effective_provider == "openai":
            findings, tokens = await _model_expert(
                request, state.get("docType", "通用文档"), domain, section, rules
            )
        else:
            findings = _mock_expert(domain, section, rules)
            tokens = estimate_tokens(
                domain,
                section["id"],
                *(segment["text"] for segment in section["segments"]),
            )
    except Exception as exc:
        error = exc
        used_fallback = True
        degraded.append(f"{agent_name} 已降级为规则审查：{exc}")
        try:
            findings = _mock_expert(domain, section, rules)
        except Exception as fallback_exc:
            findings = []
            degraded.append(f"{agent_name} 规则兜底失败：{fallback_exc}")

    result: ExpertResult = {
        "domain": domain,
        "sectionId": section["id"],
        "findings": findings,
    }
    trace = _trace(
        request.taskId,
        agent_name,
        started,
        {"domain": domain, "sectionId": section["id"], "ruleIds": [rule.id for rule in rules]},
        {"findingCount": len(findings), "fallback": used_fallback},
        tokens,
        error,
    )
    return {
        "expert_results": [result],
        "traces": [trace],
        "usage_tokens": tokens,
        "degraded_reasons": degraded,
    }


async def empty_experts_node(state: ReviewState) -> dict[str, Any]:
    return {"empty_experts": True}


async def critic_node(state: ReviewState) -> dict[str, Any]:
    started = time.perf_counter()
    request = state["request"]
    drafts = [
        finding
        for result in state.get("expert_results", [])
        for finding in result.get("findings", [])
    ]
    block_map = {block.id: block for block in request.blocks}
    accepted: list[Finding] = []
    dropped = 0
    error: Exception | None = None
    degraded: list[str] = []
    try:
        for finding in drafts:
            block = block_map.get(finding.blockId)
            if block is None or block.type.upper() == "HEADING":
                dropped += 1
                continue
            if not finding.quote or finding.quote not in block.text:
                dropped += 1
                continue
            accepted.append(finding)
    except Exception as exc:
        error = exc
        accepted = []
        dropped = len(drafts)
        degraded.append(f"CriticAgent 异常，已安全丢弃未核实结论：{exc}")

    trace = _trace(
        request.taskId,
        "CriticAgent",
        started,
        {"candidateCount": len(drafts)},
        {"acceptedCount": len(accepted), "droppedCount": dropped},
        0,
        error,
    )
    return {
        "validated_findings": accepted,
        "dropped_count": dropped,
        "traces": [trace],
        "degraded_reasons": degraded,
    }


def arbitrate_findings(candidates: list[Finding]) -> tuple[list[Finding], int]:
    merged: dict[tuple[str, str], Finding] = {}
    for finding in candidates:
        key = (finding.ruleId, finding.blockId)
        previous = merged.get(key)
        if previous is None:
            merged[key] = finding
            continue
        different_levels = previous.level != finding.level
        chosen = finding if finding.level.weight > previous.level.weight else previous
        merged[key] = chosen.model_copy(
            update={"conflict": previous.conflict or finding.conflict or different_levels}
        )
    ordered = sorted(
        merged.values(),
        key=lambda item: (-item.level.weight, item.page, item.ruleId, item.blockId),
    )
    return ordered, sum(1 for finding in ordered if finding.conflict)


def build_summary(findings: list[Finding], conflicts: int) -> str:
    if not findings:
        return "本次审查未发现命中审查清单且通过原文证据校验的风险条款。"
    high = sum(finding.level == RiskLevel.HIGH for finding in findings)
    medium = sum(finding.level == RiskLevel.MEDIUM for finding in findings)
    low = sum(finding.level == RiskLevel.LOW for finding in findings)
    parts = [
        f"本次共识别 {len(findings)} 条风险：高危 {high} 条、中危 {medium} 条、低危 {low} 条。"
    ]
    high_rules = list(dict.fromkeys(finding.ruleId for finding in findings if finding.level == RiskLevel.HIGH))
    if high_rules:
        parts.append(f"建议优先处理的高危规则：{'、'.join(high_rules[:3])}。")
    if conflicts:
        parts.append(f"其中 {conflicts} 条存在等级分歧，已按较高等级保留并标记，建议人工优先复核。")
    return "".join(parts)


async def arbiter_node(state: ReviewState) -> dict[str, Any]:
    started = time.perf_counter()
    request = state["request"]
    candidates = state.get("validated_findings", [])
    error: Exception | None = None
    degraded: list[str] = []
    try:
        findings, conflicts = arbitrate_findings(candidates)
        summary = build_summary(findings, conflicts)
    except Exception as exc:
        error = exc
        findings = []
        conflicts = 0
        summary = "仲裁节点发生异常，本次未输出未经确认的风险结论，请重试或人工复核。"
        degraded.append(f"ArbiterAgent 异常：{exc}")
    trace = _trace(
        request.taskId,
        "ArbiterAgent",
        started,
        {"candidateCount": len(candidates)},
        {"findingCount": len(findings), "conflictCount": conflicts},
        0,
        error,
    )
    return {
        "final_findings": findings,
        "summary": summary,
        "traces": [trace],
        "degraded_reasons": degraded,
    }


async def reporter_node(state: ReviewState) -> dict[str, Any]:
    summary = state.get("summary", "本次审查未生成风险结论。")
    degraded_reasons = state.get("degraded_reasons", [])
    if degraded_reasons:
        summary += " 部分节点已执行安全降级，建议结合人工复核。"
    result = {
        "docType": state.get("docType", "通用文档"),
        "experts": DOMAINS,
        "summary": summary,
        "degraded": bool(degraded_reasons),
        "findings": [
            finding.model_dump(mode="json") for finding in state.get("final_findings", [])
        ],
    }
    return {"result": result}


@lru_cache(maxsize=1)
def get_review_graph():
    builder = StateGraph(ReviewState)
    builder.add_node("planner", planner_node)
    builder.add_node("expert", expert_node)
    builder.add_node("empty_experts", empty_experts_node)
    builder.add_node("critic", critic_node)
    builder.add_node("arbiter", arbiter_node)
    builder.add_node("reporter", reporter_node)
    builder.add_edge(START, "planner")
    builder.add_conditional_edges("planner", fanout_experts, ["expert", "empty_experts"])
    builder.add_edge("expert", "critic")
    builder.add_edge("empty_experts", "critic")
    builder.add_edge("critic", "arbiter")
    builder.add_edge("arbiter", "reporter")
    builder.add_edge("reporter", END)
    return builder.compile()


def _stage(
    task_id: str,
    agent: str,
    stage: str,
    progress: int,
    message: str,
    data: dict[str, Any] | None = None,
) -> str:
    event: dict[str, Any] = {
        "messageId": task_id,
        "agent": agent,
        "stage": stage,
        "progress": progress,
        "message": message,
    }
    if data is not None:
        event["data"] = data
    return sse_event("stage", event)


async def review_event_stream(request: ReviewRequest):
    task_id = request.taskId
    total_tokens = 0
    completed_experts = 0
    total_experts = 0
    try:
        yield _stage(task_id, "PlannerAgent", "PLANNING", PROGRESS_PLANNING,
                     "正在识别文档类型并规划专家分片。")
        initial: ReviewState = {"request": request}
        async for updates in get_review_graph().astream(initial, stream_mode="updates"):
            for node, update in updates.items():
                if not isinstance(update, dict):
                    continue
                total_tokens += int(update.get("usage_tokens", 0))
                for trace in update.get("traces", []):
                    yield sse_event("trace", trace)

                if node == "planner":
                    total_experts = len(update.get("sections", [])) * len(DOMAINS)
                    yield _stage(
                        task_id,
                        "ExpertAgent",
                        "REVIEWING",
                        PROGRESS_REVIEWING,
                        f"规划完成，正在并行执行 {total_experts} 个专家分片任务。",
                        {
                            "docType": update.get("docType", "通用文档"),
                            "sections": len(update.get("sections", [])),
                            "expertTasks": total_experts,
                        },
                    )
                elif node == "expert":
                    completed_experts += 1
                    progress = PROGRESS_REVIEWING + int(
                        PROGRESS_REVIEWING_SPAN * completed_experts / max(total_experts, 1)
                    )
                    results = update.get("expert_results", [])
                    section_id = results[0]["sectionId"] if results else ""
                    yield _stage(
                        task_id,
                        "ExpertAgent",
                        "REVIEWING",
                        progress,
                        f"专家分片已完成 {completed_experts}/{total_experts}。",
                        {"sectionId": section_id},
                    )
                    if completed_experts == total_experts:
                        yield _stage(task_id, "CriticAgent", "CRITIQUING", PROGRESS_CRITIQUING,
                                     "正在逐字核验风险结论的原文证据。")
                elif node == "empty_experts":
                    yield _stage(task_id, "CriticAgent", "CRITIQUING", PROGRESS_CRITIQUING,
                                 "没有可审查正文，正在执行空结果校验。")
                elif node == "critic":
                    yield _stage(
                        task_id,
                        "ArbiterAgent",
                        "ARBITRATING",
                        PROGRESS_ARBITRATING,
                        "证据校验完成，正在去重、处理等级冲突并汇总。",
                        {"dropped": update.get("dropped_count", 0)},
                    )
                elif node == "arbiter":
                    yield _stage(task_id, "ReporterAgent", "REPORTING", PROGRESS_REPORTING,
                                 "仲裁完成，正在生成审查报告。")
                elif node == "reporter":
                    # 100% 只能由 Java 在结果落库后发布，避免前端提前读取报告。
                    yield _stage(task_id, "ReporterAgent", "REPORTING", PROGRESS_SAVING,
                                 "审查报告已生成，正在保存结果。")
                    yield sse_event(
                        "done",
                        {
                            "messageId": task_id,
                            "result": update["result"],
                            "usage": {"tokens": total_tokens},
                        },
                    )
    except Exception as exc:
        yield error_event(task_id, f"审查流水线执行失败：{exc}", retryable=True)
