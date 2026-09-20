# RAGAS Evaluation

This directory contains optional Python tooling for RAGAS evaluation. The Java application does not depend on RAGAS; it only exports `target/rag-eval-report.json`.

Run from the repository root:

```bash
python3 -m pip install -r eval/requirements-ragas.txt
```

OpenAI judge:

```bash
OPENAI_API_KEY=... \
python3 eval/run-ragas-eval.py \
  --provider openai \
  --input target/rag-eval-report.json \
  --output target/ragas-report.json
```

Optional local Ollama judge (choose your own installed model; the main app uses DeepSeek API):

```bash
python3 eval/run-ragas-eval.py \
  --provider ollama \
  --judge-model YOUR_LOCAL_CHAT_MODEL \
  --embedding-model YOUR_LOCAL_EMBEDDING_MODEL \
  --input target/rag-eval-report.json \
  --output target/ragas-report.json
```

For slower local models, reduce concurrency:

```bash
python3 eval/run-ragas-eval.py \
  --provider ollama \
  --judge-model YOUR_LOCAL_CHAT_MODEL \
  --embedding-model YOUR_LOCAL_EMBEDDING_MODEL \
  --max-workers 1 \
  --batch-size 1
```
