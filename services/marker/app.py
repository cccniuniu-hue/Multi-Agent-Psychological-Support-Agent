import asyncio
import base64
import io
import tempfile
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

from fastapi import FastAPI, File, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from marker.converters.pdf import PdfConverter
from marker.models import create_model_dict
from marker.output import text_from_rendered
from marker.settings import settings as marker_settings

from contract import ContractError, load_settings, validate_filename, validate_size


app = FastAPI(title="MindBridge Marker Service")
service_settings = load_settings()
executor = ThreadPoolExecutor(max_workers=1)


def remove_file(path: str) -> None:
    Path(path).unlink(missing_ok=True)


@lru_cache(maxsize=1)
def get_converter() -> PdfConverter:
    return PdfConverter(artifact_dict=create_model_dict())


def convert_document(path: str) -> tuple[str, dict, dict[str, str]]:
    rendered = get_converter()(path)
    markdown, metadata, images = text_from_rendered(rendered)
    encoded_images = {}
    for name, image in images.items():
        output = io.BytesIO()
        image.save(output, format=marker_settings.OUTPUT_IMAGE_FORMAT)
        encoded_images[name] = base64.b64encode(output.getvalue()).decode(marker_settings.OUTPUT_ENCODING)
    return markdown, metadata, encoded_images


async def read_limited(file: UploadFile) -> bytes:
    content = bytearray()
    while chunk := await file.read(1024 * 1024):
        content.extend(chunk)
        validate_size(len(content), service_settings.max_file_bytes)
    validate_size(len(content), service_settings.max_file_bytes)
    return bytes(content)


@app.exception_handler(ContractError)
async def contract_error_handler(_request, exception: ContractError):
    return JSONResponse(status_code=exception.status_code, content=exception.body())


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(_request, _exception):
    error = ContractError(400, "invalid_request", "请求必须包含 file 文件")
    return JSONResponse(status_code=error.status_code, content=error.body())


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/marker/upload")
async def upload(file: UploadFile = File(...)):
    source, extension = validate_filename(file.filename)
    content = await read_limited(file)
    temporary_path = None
    future = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=extension) as temporary:
            temporary.write(content)
            temporary_path = temporary.name
        future = executor.submit(convert_document, temporary_path)
        markdown, metadata, images = await asyncio.wait_for(
            asyncio.wrap_future(future), timeout=service_settings.timeout_seconds)
        return {
            "success": True,
            "source": source,
            "title": Path(source).stem,
            "output": markdown,
            "images": images,
            "metadata": metadata,
        }
    except TimeoutError as exception:
        if future is not None:
            future.add_done_callback(lambda _future, path=temporary_path: remove_file(path))
            temporary_path = None
        raise ContractError(504, "conversion_timeout", "文档解析超时") from exception
    except ContractError:
        raise
    except Exception as exception:
        raise ContractError(422, "conversion_failed", "文档解析失败") from exception
    finally:
        if temporary_path:
            remove_file(temporary_path)
