from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class RiskLevel(str, Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"

    @property
    def weight(self) -> int:
        return {"HIGH": 3, "MEDIUM": 2, "LOW": 1}[self.value]


class Block(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    page: int = 0
    sectionPath: str = ""
    seq: int = 0
    text: str
    type: str = "PARAGRAPH"


class ReviewRule(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    domain: str
    title: str
    level: RiskLevel
    keywords: list[str] = Field(default_factory=list)
    reason: str = ""
    suggestion: str = ""


class ReviewRequest(BaseModel):
    taskId: str
    documentName: str
    blocks: list[Block]
    rules: list[ReviewRule]
    sectionMaxChars: int = Field(default=800, gt=0)


class Finding(BaseModel):
    model_config = ConfigDict(extra="ignore")

    agentName: str = "ReviewAgent"
    ruleId: str = ""
    level: RiskLevel = RiskLevel.MEDIUM
    blockId: str
    page: int = 0
    quote: str = ""
    reason: str = ""
    suggestion: str = ""
    conflict: bool = False


class ChatHistoryItem(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messageId: str
    taskId: str
    documentId: str
    message: str
    history: list[ChatHistoryItem] = Field(default_factory=list)
    javaBaseUrl: str = "http://127.0.0.1:8080"

    @field_validator("javaBaseUrl")
    @classmethod
    def validate_java_base_url(cls, value: str) -> str:
        value = value.rstrip("/")
        if not value.startswith(("http://", "https://")):
            raise ValueError("javaBaseUrl must use http or https")
        return value


class Citation(BaseModel):
    blockId: str
    quote: str
