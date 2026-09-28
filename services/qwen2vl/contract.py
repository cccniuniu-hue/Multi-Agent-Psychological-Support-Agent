import json
import os
from dataclasses import dataclass
from pathlib import Path


ALLOWED_IMAGES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
}


@dataclass(frozen=True, slots=True)
class Settings:
    model: str
    max_image_bytes: int
    timeout_seconds: float
    max_new_tokens: int


class ContractError(ValueError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.status_code = status_code
        self.code = code
        self.message = message

    def body(self) -> dict:
        return {"success": False, "error": {"code": self.code, "message": self.message}}


def load_settings() -> Settings:
    settings = Settings(
        model=os.getenv("QWEN2VL_MODEL", "Qwen/Qwen2-VL-7B-Instruct"),
        max_image_bytes=int(os.getenv("QWEN2VL_MAX_IMAGE_BYTES", str(10 * 1024 * 1024))),
        timeout_seconds=float(os.getenv("QWEN2VL_TIMEOUT_SECONDS", "60")),
        max_new_tokens=int(os.getenv("QWEN2VL_MAX_NEW_TOKENS", "512")),
    )
    if (not settings.model.strip() or settings.max_image_bytes <= 0
            or settings.timeout_seconds <= 0 or settings.max_new_tokens <= 0):
        raise ValueError("Qwen2-VL settings must be positive and model must not be blank")
    return settings


def validate_image(filename: str | None, content_type: str | None,
                   size: int, max_image_bytes: int) -> tuple[str, str]:
    source = Path((filename or "").replace("\\", "/")).name
    expected_type = ALLOWED_IMAGES.get(Path(source).suffix.lower())
    if not source or expected_type is None or content_type != expected_type:
        raise ContractError(415, "unsupported_image_type", "仅支持 PNG、JPEG 和 WebP 图片")
    if size <= 0:
        raise ContractError(400, "empty_image", "图片内容为空")
    if size > max_image_bytes:
        raise ContractError(413, "image_too_large", f"图片不能超过 {max_image_bytes} 字节")
    return source, expected_type


def parse_model_output(raw: str) -> dict:
    start = raw.find("{")
    end = raw.rfind("}")
    try:
        value = json.loads(raw[start:end + 1]) if start >= 0 and end > start else None
    except json.JSONDecodeError as exception:
        raise ContractError(502, "invalid_model_response", "图片描述模型未返回有效 JSON") from exception
    if not isinstance(value, dict):
        raise ContractError(502, "invalid_model_response", "图片描述模型未返回有效 JSON")

    summary = value.get("summary")
    if not isinstance(summary, str) or not summary.strip():
        raise ContractError(502, "invalid_model_response", "图片描述缺少 summary")
    return {
        "image_type": _text(value.get("image_type"), "other"),
        "summary": summary.strip(),
        "core_elements": _text_list(value.get("core_elements")),
        "key_relations": _text_list(value.get("key_relations")),
        "data_insights": _text_list(value.get("data_insights")),
    }


def _text(value, default: str) -> str:
    return value.strip() if isinstance(value, str) and value.strip() else default


def _text_list(value) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item.strip() for item in value if isinstance(item, str) and item.strip()]
