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

## Metrics and report contract

The default metrics are the following four. Names and definitions follow the [RAGAS 0.3.0 metric reference](https://docs.ragas.io/en/v0.3.0/references/metrics/).

| CLI metric class | JSON score key | Meaning |
| --- | --- | --- |
| `Faithfulness` | `faithfulness` | Whether claims in the answer are supported by the retrieved contexts. |
| `ResponseRelevancy` | `answer_relevancy` | Whether the answer addresses the question; this metric also needs embeddings. |
| `LLMContextPrecisionWithReference` | `llm_context_precision_with_reference` | Whether relevant contexts, judged against the reference answer, appear early in retrieval order. |
| `LLMContextRecall` | `context_recall` | Whether retrieved contexts cover the information in the reference answer. |

`FactualCorrectness` is optional: add it explicitly with `--metrics "Faithfulness,ResponseRelevancy,LLMContextPrecisionWithReference,LLMContextRecall,FactualCorrectness"`. If a requested metric is unavailable, the script exits instead of silently omitting it. Mode-specific score keys such as `factual_correctness(mode=f1)` are preserved.

The script uses the public `EvaluationResult.scores` interface and `raise_exceptions=False`, as described in the [RAGAS 0.3.0 evaluation reference](https://docs.ragas.io/en/v0.3.0/references/evaluate/). The dependency range remains in `requirements-ragas.txt`; an incompatible result shape or missing metric column is an error, rather than a report with misaligned case IDs.

The output records:

- `scores`: each metric's mean over finite values only. Real zero scores count; missing, NaN and infinite values become JSON `null`, never zero. A metric without any valid scores has a `null` mean.
- `metricCoverage`: `validCount` and `failedCount` for each metric, over eligible cases. Check these counts before comparing means.
- `status`: `complete` when every eligible case has every score and no cases were skipped; `partial` when some scores failed or cases were skipped; `failed` when no metric has any finite score. An all-failed evaluation writes the report and exits with a nonzero code. These states describe evaluation completeness, not a RAG quality threshold.
- `inputCaseCount`, `caseCount`, `skippedCaseCount`, `skippedCases`: input, eligible and skipped counts plus skip reasons. Empty contexts or blank required text are skipped; malformed field types, duplicate IDs, an empty report or all cases skipped cause an error.
- `cases`: input text, source/intent/risk metadata and per-case scores, aligned by input order after filtering.
- `evaluation`: UTC evaluation time, provider, judge and embedding models, requested metric classes, RAGAS version, timeout, concurrency and batch size. It contains no API keys.
- `dataset`, `sourceEvaluatedAt`, `fusionStrategy`, `topK`: copied from the Java report where available; absent metadata remains `null`.

Keep the dataset, knowledge snapshot, topK, evaluator models, RAGAS version and execution parameters fixed when comparing RRF and weighted fusion. Save different input/output filenames under `target/`. Skipped empty-context cases are excluded from metric means, so these means alone do not measure retrieval success across the full input dataset.

## Windows and service requirements

In PowerShell, set `OPENAI_API_KEY` only in the current environment, then run:

```powershell
python eval/run-ragas-eval.py `
  --provider openai `
  --judge-model gpt-4o-mini `
  --embedding-model text-embedding-3-small `
  --input target/rag-eval-report.json `
  --output target/ragas-report.json
```

`--timeout`, `--max-workers` and an explicitly supplied `--batch-size` must be positive integers. Input and output must be different files. Evaluation needs the selected judge and embedding services; offline evaluation does not mean disconnected evaluation. The OpenAI evaluator defaults shown above are separate from the main application's DeepSeek chat configuration and the disabled-by-default user-profile embedding. A DeepSeek chat API key does not provide an OpenAI embedding service.

Real scoring sends questions, answers, contexts and references to the selected evaluator. Use synthetic or appropriately desensitized data; keep keys, real psychological conversations and generated evaluation reports out of Git. The committed example contains no measured scores. GitHub Actions runs only the dependency-free checks and input validation above, with no model calls or optional RAGAS installation.
