import json

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app


@pytest.fixture(autouse=True)
def reset_settings(monkeypatch):
    monkeypatch.setenv("APP_LLM_PROVIDER", "mock")
    monkeypatch.delenv("APP_LLM_API_KEY", raising=False)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


def collect_sse(lines):
    events = []
    event_name = None
    data_lines = []
    for line in lines:
        if line == "":
            if event_name is not None:
                events.append({"event": event_name, "data": json.loads("\n".join(data_lines))})
            event_name = None
            data_lines = []
        elif line.startswith("event:"):
            event_name = line.split(":", 1)[1].strip()
        elif line.startswith("data:"):
            data_lines.append(line.split(":", 1)[1].strip())
    if event_name is not None:
        events.append({"event": event_name, "data": json.loads("\n".join(data_lines))})
    return events


def post_sse(client, path, payload):
    with client.stream("POST", path, json=payload) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        events = collect_sse(response.iter_lines())
    return events
