import math
import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Settings:
    model: str
    max_candidates: int
    timeout_seconds: float


class ContractError(ValueError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message

    def body(self) -> dict:
        return {"success": False, "error": {"code": self.code, "message": self.message}}


def load_settings() -> Settings:
    settings = Settings(
        model=os.getenv("BGE_RERANKER_MODEL", "BAAI/bge-reranker-v2-m3"),
        max_candidates=int(os.getenv("BGE_RERANKER_MAX_CANDIDATES", "50")),
        timeout_seconds=float(os.getenv("BGE_RERANKER_TIMEOUT_SECONDS", "30")),
    )
    if not settings.model.strip() or settings.max_candidates <= 0 or settings.timeout_seconds <= 0:
        raise ValueError("Reranker settings must be positive and model must not be blank")
    return settings


def validate_request(payload: object, max_candidates: int) -> tuple[str, list[str]]:
    if not isinstance(payload, dict):
        raise ContractError(400, "invalid_request", "请求必须是 JSON 对象")
    query = payload.get("query")
    passages = payload.get("passages")
    if not isinstance(query, str) or not query.strip():
        raise ContractError(400, "invalid_request", "query 不能为空")
    if not isinstance(passages, list) or not passages:
        raise ContractError(400, "invalid_request", "passages 必须是非空数组")
    if len(passages) > max_candidates:
        raise ContractError(413, "too_many_passages", f"passages 不能超过 {max_candidates} 条")
    if any(not isinstance(passage, str) or not passage.strip() for passage in passages):
        raise ContractError(400, "invalid_request", "passages 必须包含非空文本")
    if len(query) > 4000 or any(len(passage) > 4000 for passage in passages):
        raise ContractError(413, "text_too_long", "query 和单条 passage 不能超过 4000 字符")
    return query.strip(), [passage.strip() for passage in passages]


def format_scores(raw_scores: object, count: int, model: str) -> dict:
    try:
        scores = list(raw_scores)
    except TypeError:
        scores = [raw_scores] if count == 1 else []
    if len(scores) != count:
        raise ContractError(502, "invalid_model_response", "模型返回的分数数量不匹配")
    try:
        numbers = [float(score) for score in scores]
    except (TypeError, ValueError) as exception:
        raise ContractError(502, "invalid_model_response", "模型返回了无效分数") from exception
    if any(not math.isfinite(score) for score in numbers):
        raise ContractError(502, "invalid_model_response", "模型返回了无效分数")
    return {
        "success": True,
        "model": model,
        "scores": [{"index": index, "score": score} for index, score in enumerate(numbers)],
    }
