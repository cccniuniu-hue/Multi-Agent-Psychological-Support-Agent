import asyncio
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, File, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from contract import ContractError, load_settings
from qwen_client import Qwen2VlClient
from service import describe_image


settings = load_settings()
client = Qwen2VlClient(settings)
executor = ThreadPoolExecutor(max_workers=1)
app = FastAPI(title="MindBridge Qwen2-VL Service")


@app.exception_handler(ContractError)
async def contract_error_handler(_request, exception: ContractError):
    return JSONResponse(status_code=exception.status_code, content=exception.body())


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(_request, _exception):
    error = ContractError(400, "invalid_request", "请求必须包含 file 图片")
    return JSONResponse(status_code=error.status_code, content=error.body())


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": client.model_name}


@app.post("/qwen2-vl/describe")
async def describe(file: UploadFile = File(...)):
    content = bytearray()
    while chunk := await file.read(1024 * 1024):
        content.extend(chunk)
        if len(content) > settings.max_image_bytes:
            raise ContractError(
                413, "image_too_large", f"图片不能超过 {settings.max_image_bytes} 字节")
    future = executor.submit(
        describe_image, client, file.filename, file.content_type, bytes(content), settings)
    try:
        return await asyncio.wait_for(
            asyncio.wrap_future(future), timeout=settings.timeout_seconds)
    except TimeoutError as exception:
        raise ContractError(504, "description_timeout", "图片描述超时") from exception
    except ContractError:
        raise
    except Exception as exception:
        raise ContractError(502, "description_failed", "图片描述失败") from exception
