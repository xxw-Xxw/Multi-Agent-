import httpx

from conftest import post_sse

B1 = {
    "id": "b1",
    "page": 3,
    "sectionPath": "第四章/违约责任",
    "seq": 1,
    "text": "任何一方违约，应向守约方支付合同总金额 30% 的违约金。",
    "type": "PARAGRAPH",
}
B2 = {
    "id": "b2",
    "page": 4,
    "sectionPath": "第四章/解除",
    "seq": 2,
    "text": "甲方有权单方面解除本合同，无需承担任何责任。",
    "type": "PARAGRAPH",
}


def chat_payload(message="违约金是怎么约定的？", history=None):
    return {
        "messageId": "message-1",
        "taskId": "task-1",
        "documentId": "doc-1",
        "message": message,
        "history": history or [],
        "javaBaseUrl": "http://java.test",
    }


def install_java_mock(monkeypatch, handler):
    transport = httpx.MockTransport(handler)
    async_client = httpx.AsyncClient

    def factory(*args, **kwargs):
        kwargs["transport"] = transport
        return async_client(*args, **kwargs)

    monkeypatch.setattr("app.chat.httpx.AsyncClient", factory)


def test_chat_search_answer_tokens_and_citations(client, monkeypatch):
    def java_handler(request):
        assert request.url.path == "/internal/docs/doc-1/blocks/search"
        assert request.url.params["q"] == "违约金"
        assert request.url.params["limit"] == "8"
        return httpx.Response(200, json=[B1])

    install_java_mock(monkeypatch, java_handler)
    events = post_sse(client, "/internal/chat", chat_payload())
    assert not [event for event in events if event["event"] == "error"]
    tool_calls = [event["data"] for event in events if event["event"] == "tool_call"]
    assert tool_calls == [
        {"messageId": "message-1", "tool": "search_blocks", "args": {"q": "违约金", "limit": 8}}
    ]
    answer = "".join(event["data"]["delta"] for event in events if event["event"] == "token")
    assert "违约金" in answer
    assert "合同总金额 30%" in answer
    done = next(event["data"] for event in events if event["event"] == "done")
    assert done["citations"]
    for citation in done["citations"]:
        assert citation["quote"] in B1["text"]
        assert citation["quote"] in answer
    assert all(
        event["data"]["messageId"] == "message-1"
        for event in events
        if event["event"] in {"tool_call", "token", "done"}
    )


def test_chat_risk_summary_uses_findings_then_at_most_three_tools(client, monkeypatch):
    findings = [
        {
            "agentName": "法务专家Agent",
            "ruleId": "LEGAL-02",
            "level": "MEDIUM",
            "blockId": "b1",
            "page": 3,
            "quote": "合同总金额 30% 的违约金",
            "reason": "违约金比例需与实际损失匹配。",
            "suggestion": "明确计算基数与上限。",
            "conflict": False,
        },
        {
            "agentName": "法务专家Agent",
            "ruleId": "LEGAL-01",
            "level": "HIGH",
            "blockId": "b2",
            "page": 4,
            "quote": "单方面解除本合同",
            "reason": "单方解除权导致权利义务失衡。",
            "suggestion": "增加通知期限与补偿责任。",
            "conflict": False,
        },
    ]

    def java_handler(request):
        path = request.url.path
        if path == "/internal/tasks/task-1/findings":
            return httpx.Response(200, json=findings)
        if path == "/internal/docs/doc-1/blocks/b1":
            return httpx.Response(200, json=B1)
        if path == "/internal/docs/doc-1/blocks/b2":
            return httpx.Response(200, json=B2)
        return httpx.Response(404, json={"path": path})

    install_java_mock(monkeypatch, java_handler)
    events = post_sse(client, "/internal/chat", chat_payload("请总结这份合同的整体风险"))
    tools = [event["data"]["tool"] for event in events if event["event"] == "tool_call"]
    assert tools == ["get_findings", "get_block", "get_block"]
    answer = "".join(event["data"]["delta"] for event in events if event["event"] == "token")
    assert "共记录 2 条风险" in answer
    assert "高危 1 条" in answer
    assert "违约金" in answer
    done = next(event["data"] for event in events if event["event"] == "done")
    assert len(done["citations"]) == 2
    block_map = {"b1": B1, "b2": B2}
    for citation in done["citations"]:
        assert citation["quote"] in block_map[citation["blockId"]]["text"]


def test_chat_original_followup_uses_history_for_query(client, monkeypatch):
    def java_handler(request):
        assert request.url.params["q"] == "违约金"
        return httpx.Response(200, json=[B1])

    install_java_mock(monkeypatch, java_handler)
    history = [
        {"role": "user", "content": "违约金有什么约定？"},
        {"role": "assistant", "content": "需要查看违约金条款。"},
    ]
    events = post_sse(client, "/internal/chat", chat_payload("这条原文怎么说的？", history))
    answer = "".join(event["data"]["delta"] for event in events if event["event"] == "token")
    assert "相关原文如下" in answer
    assert "合同总金额 30%" in answer
    done = next(event["data"] for event in events if event["event"] == "done")
    assert done["citations"][0]["blockId"] == "b1"


def test_chat_no_search_result_does_not_invent_answer(client, monkeypatch):
    def java_handler(request):
        return httpx.Response(200, json=[])

    install_java_mock(monkeypatch, java_handler)
    events = post_sse(client, "/internal/chat", chat_payload("不存在的特殊条款是什么？"))
    answer = "".join(event["data"]["delta"] for event in events if event["event"] == "token")
    assert "未找到" in answer
    assert "不能据此作答" in answer
    done = next(event["data"] for event in events if event["event"] == "done")
    assert done["citations"] == []
