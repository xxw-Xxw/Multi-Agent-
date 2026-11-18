import app.review as review_module
from app.config import get_settings

from conftest import post_sse


def rules():
    return [
        {
            "id": "LEGAL-01",
            "domain": "LEGAL",
            "title": "单方解除权且免除责任",
            "level": "HIGH",
            "keywords": ["单方面解除", "单方解除", "无需承担任何责任"],
            "reason": "权利义务显著失衡。",
            "suggestion": "增加通知期限与补偿责任。",
        },
        {
            "id": "LEGAL-02",
            "domain": "LEGAL",
            "title": "违约责任与违约金约定",
            "level": "MEDIUM",
            "keywords": ["违约金", "违约方"],
            "reason": "违约金需与实际损失匹配。",
            "suggestion": "明确计算基数与上限。",
        },
        {
            "id": "FIN-01",
            "domain": "FINANCE",
            "title": "预付款比例",
            "level": "MEDIUM",
            "keywords": ["预付款", "预付"],
            "reason": "预付款放大资金风险。",
            "suggestion": "控制比例并要求担保。",
        },
        {
            "id": "COMP-01",
            "domain": "COMPLIANCE",
            "title": "个人信息处理",
            "level": "HIGH",
            "keywords": ["个人信息", "身份证", "手机号"],
            "reason": "需满足最小必要原则。",
            "suggestion": "补充处理范围并取得同意。",
        },
    ]


def review_payload():
    return {
        "taskId": "task-review-1",
        "documentName": "采购合同示例.txt",
        "blocks": [
            {"id": "h1", "page": 1, "sectionPath": "标题", "seq": 0, "text": "违约金标题", "type": "HEADING"},
            {
                "id": "b1",
                "page": 1,
                "sectionPath": "第四章/违约责任",
                "seq": 1,
                "text": "任何一方违约，应支付合同总金额 30% 的违约金。",
                "type": "PARAGRAPH",
            },
            {
                "id": "b2",
                "page": 2,
                "sectionPath": "第四章/违约责任",
                "seq": 2,
                "text": "甲方有权单方面解除本合同，无需承担任何责任。",
                "type": "PARAGRAPH",
            },
            {
                "id": "b3",
                "page": 2,
                "sectionPath": "其他",
                "seq": 3,
                "text": "本条款与规则无关。",
                "type": "PARAGRAPH",
            },
        ],
        "rules": rules(),
        "sectionMaxChars": 20,
    }


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "framework": "langgraph", "provider": "mock"}


def test_review_sse_mock_rules_and_heading_excluded(client):
    events = post_sse(client, "/internal/review", review_payload())
    assert not [event for event in events if event["event"] == "error"]
    stages = [event["data"]["stage"] for event in events if event["event"] == "stage"]
    for required in ["PLANNING", "REVIEWING", "CRITIQUING", "ARBITRATING", "REPORTING"]:
        assert required in stages
    assert all(
        event["data"]["messageId"] == "task-review-1"
        for event in events
        if event["event"] in {"stage", "trace", "done"}
    )

    traces = [event["data"] for event in events if event["event"] == "trace"]
    planner = next(trace for trace in traces if trace["agentName"] == "PlannerAgent")
    section_count = planner["output"]["sectionCount"]
    expert_traces = [trace for trace in traces if "专家Agent#" in trace["agentName"]]
    assert len(expert_traces) == section_count * 3
    assert {trace["input"]["domain"] for trace in expert_traces} == {"LEGAL", "FINANCE", "COMPLIANCE"}
    assert sum(trace["agentName"] == "CriticAgent" for trace in traces) == 1
    assert sum(trace["agentName"] == "ArbiterAgent" for trace in traces) == 1

    done = next(event["data"] for event in events if event["event"] == "done")
    result = done["result"]
    assert result["docType"] == "采购合同"
    assert result["experts"] == ["LEGAL", "FINANCE", "COMPLIANCE"]
    assert result["degraded"] is False
    assert result["findings"]
    assert "高危" in result["summary"] and "中危" in result["summary"]
    block_map = {block["id"]: block for block in review_payload()["blocks"]}
    for finding in result["findings"]:
        assert finding["blockId"] != "h1"
        assert finding["quote"] in block_map[finding["blockId"]]["text"]
        assert finding["level"] in {"HIGH", "MEDIUM", "LOW"}
    assert {finding["ruleId"] for finding in result["findings"]} >= {"LEGAL-01", "LEGAL-02"}
    assert done["usage"]["tokens"] > 0


def test_review_model_json_fake_quote_dropped_and_conflict_arbitrated(client, monkeypatch):
    monkeypatch.setenv("APP_LLM_PROVIDER", "openai")
    monkeypatch.setenv("APP_LLM_API_KEY", "offline-test-key")
    get_settings.cache_clear()

    async def fake_complete_json(system, user, settings):
        if "PLANNER" in system:
            return {"docType": "采购合同"}, '{"docType":"采购合同"}', 3
        if "EXPERT:LEGAL" not in system or "blockId=b1" not in user:
            return {"findings": []}, '{"findings":[]}', 2
        findings = [
            {
                "ruleId": "LEGAL-02",
                "level": "LOW",
                "blockId": "b1",
                "quote": "违约金",
                "reason": "低等级判断",
                "suggestion": "复核",
            },
            {
                "ruleId": "LEGAL-02",
                "level": "HIGH",
                "blockId": "b1",
                "quote": "违约金",
                "reason": "高等级判断",
                "suggestion": "优先复核",
            },
            {
                "ruleId": "LEGAL-02",
                "level": "HIGH",
                "blockId": "b1",
                "quote": "违 约 金 是不存在的原文",
                "reason": "伪造证据",
                "suggestion": "不应出现",
            },
        ]
        return {"findings": findings}, "model-json", 7

    monkeypatch.setattr(review_module, "complete_json", fake_complete_json)
    events = post_sse(client, "/internal/review", review_payload())
    assert not [event for event in events if event["event"] == "error"]
    critic = next(
        event["data"]
        for event in events
        if event["event"] == "trace" and event["data"]["agentName"] == "CriticAgent"
    )
    assert critic["output"]["droppedCount"] >= 1

    done = next(event["data"] for event in events if event["event"] == "done")
    findings = [finding for finding in done["result"]["findings"] if finding["ruleId"] == "LEGAL-02"]
    assert len(findings) == 1
    assert findings[0]["level"] == "HIGH"
    assert findings[0]["conflict"] is True
    assert findings[0]["quote"] == "违约金"
    assert "伪造证据" not in str(done)
    assert "等级分歧" in done["result"]["summary"]
