# BGE Reranker HTTP service

This optional service uses [BAAI/bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) through FlagEmbedding. Start it with `docker compose --profile reranker up -d reranker`. The model is loaded on the first scoring request. The Java retrieval client is scheduled for the next implementation step.

`POST /rerank` accepts one query and 1–50 passages, each at most 4000 characters:

```json
{"query":"如何缓解焦虑？","passages":["先做呼吸练习。","联系学校心理中心。"]}
```

Success (HTTP 200):

```json
{"success":true,"model":"BAAI/bge-reranker-v2-m3","scores":[{"index":0,"score":1.2},{"index":1,"score":-0.4}]}
```

`index` is zero-based and follows input order. Scores are raw model scores; larger means more relevant. The service also exposes `GET /health`.

Errors use `{"success":false,"error":{"code":"...","message":"..."}}`: `invalid_request` (400), `too_many_passages` or `text_too_long` (413), `invalid_model_response` or `rerank_failed` (502), and `rerank_timeout` (504). Configure `BGE_RERANKER_MODEL`, `BGE_RERANKER_MAX_CANDIDATES`, and `BGE_RERANKER_TIMEOUT_SECONDS` through the environment.
