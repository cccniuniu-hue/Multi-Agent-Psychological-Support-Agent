import asyncio
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from contract import ContractError, load_settings, validate_request
from model import BgeReranker
from service import score_passages


settings = load_settings()
client = BgeReranker(settings.model)
# ponytail: one worker serializes model calls; use replicas if throughput matters.
executor = ThreadPoolExecutor(max_workers=1)
app = FastAPI(title="BGE Reranker Service")


@app.exception_handler(ContractError)
async def contract_error_handler(_request, exception: ContractError):
    return JSONResponse(status_code=exception.status_code, content=exception.body())


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(_request, _exception):
    error = ContractError(400, "invalid_request", "请求必须包含 query 和 passages")
    return JSONResponse(status_code=error.status_code, content=error.body())


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": client.model_name}


@app.post("/rerank")
async def rerank(payload: dict):
    query, passages = validate_request(payload, settings.max_candidates)
    future = executor.submit(score_passages, client, query, passages)
    try:
        return await asyncio.wait_for(
            asyncio.wrap_future(future), timeout=settings.timeout_seconds)
    except TimeoutError as exception:
        raise ContractError(504, "rerank_timeout", "重排超时") from exception
    except ContractError:
        raise
    except Exception as exception:
        raise ContractError(502, "rerank_failed", "重排模型评分失败") from exception
