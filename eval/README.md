# RAGAS Evaluation

This directory contains optional Python tooling for RAGAS evaluation. The Java application does not depend on RAGAS; it only exports `target/rag-eval-report.json`.

Check the input without installing RAGAS, setting API keys or starting any services:

```bash
python3 eval/run-ragas-eval.py --input eval/examples/rag-eval-report.synthetic.json --validate-only
python3 -m unittest discover -s eval -p 'test_ragas_eval.py' -v
```

The synthetic example has two eligible cases and one empty-context case that is explicitly skipped. It contains fictional text, no real conversations or measured scores. Validation prints counts and skip reasons; it writes no output file and does not measure RAG quality. Use the same flag with `target/rag-eval-report.json` to check a real Java export. Case IDs must be unique non-empty strings; text fields must be strings and `retrievedContexts` must be a list of strings.

To compare fusion strategies, run the Java evaluation twice on the same dataset and knowledge snapshot: once with `RAG_FUSION_STRATEGY=rrf` and `RAG_EVAL_OUTPUT_PATH=target/rag-eval-rrf.json`, then with `RAG_FUSION_STRATEGY=weighted` and `RAG_EVAL_OUTPUT_PATH=target/rag-eval-weighted.json`. Each report records `fusionStrategy`; compare retrieval results and optionally pass each report to the RAGAS command below.

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
