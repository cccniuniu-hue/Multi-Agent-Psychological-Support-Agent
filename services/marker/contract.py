import os
from dataclasses import dataclass
from pathlib import Path


ALLOWED_EXTENSIONS = {".pdf", ".docx"}


@dataclass(frozen=True, slots=True)
class Settings:
    max_file_bytes: int
    timeout_seconds: float


class ContractError(ValueError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.status_code = status_code
        self.code = code
        self.message = message

    def body(self) -> dict:
        return {"success": False, "error": {"code": self.code, "message": self.message}}


def load_settings() -> Settings:
    max_file_bytes = int(os.getenv("MARKER_MAX_FILE_BYTES", str(10 * 1024 * 1024)))
    timeout_seconds = float(os.getenv("MARKER_TIMEOUT_SECONDS", "120"))
    if max_file_bytes <= 0 or timeout_seconds <= 0:
        raise ValueError("Marker size and timeout limits must be positive")
    return Settings(max_file_bytes=max_file_bytes, timeout_seconds=timeout_seconds)


def validate_filename(filename: str | None) -> tuple[str, str]:
    source = Path((filename or "").replace("\\", "/")).name
    extension = Path(source).suffix.lower()
    if not source or extension not in ALLOWED_EXTENSIONS:
        raise ContractError(415, "unsupported_file_type", "仅支持 PDF 和 DOCX 文件")
    return source, extension


def validate_size(size: int, max_file_bytes: int) -> None:
    if size <= 0:
        raise ContractError(400, "empty_file", "文件内容为空")
    if size > max_file_bytes:
        raise ContractError(413, "file_too_large", f"文件不能超过 {max_file_bytes} 字节")
