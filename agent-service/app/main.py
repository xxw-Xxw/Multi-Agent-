from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from .chat import chat_event_stream
from .config import get_settings
from .review import review_event_stream
from .schemas import ChatRequest, ReviewRequest

app = FastAPI(title="Document Review Agent Service")

STREAM_HEADERS = {
    "Cache-Control": "no-cache",
    "X-Accel-Buffering": "no",
}


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "framework": "langgraph",
        "provider": get_settings().effective_provider,
    }


@app.post("/internal/review")
async def internal_review(request: ReviewRequest):
    return StreamingResponse(
        review_event_stream(request),
        media_type="text/event-stream",
        headers=STREAM_HEADERS,
    )


@app.post("/internal/chat")
async def internal_chat(request: ChatRequest):
    return StreamingResponse(
        chat_event_stream(request),
        media_type="text/event-stream",
        headers=STREAM_HEADERS,
    )
