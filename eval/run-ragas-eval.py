#!/usr/bin/env python3
"""Run RAGAS metrics over the MindBridge RAG evaluation report."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
from typing import Any


INPUT_COLUMNS = {"user_input", "response", "retrieved_contexts", "reference"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAGAS over target/rag-eval-report.json.")
    parser.add_argument("--input", default="target/rag-eval-report.json", help="Java RAG eval report path.")
    parser.add_argument("--output", default="target/ragas-report.json", help="RAGAS JSON report path.")
    parser.add_argument("--provider", choices=["openai", "ollama"], default="openai", help="Evaluator model provider.")
    parser.add_argument("--judge-model", default=None, help="LLM-as-judge model name.")
    parser.add_argument("--embedding-model", default=None, help="Embedding model name.")
    parser.add_argument("--ollama-base-url", default=os.getenv("OLLAMA_BASE_URL", "http://localhost:11434"))
    parser.add_argument("--batch-size", type=int, default=None)
    parser.add_argument(
        "--metrics",
        default="LLMContextPrecisionWithReference,LLMContextRecall,ResponseRelevancy,Faithfulness,FactualCorrectness",
        help="Comma-separated RAGAS metric class names to run.",
    )
    parser.add_argument("--timeout", type=int, default=180, help="Per-job RAGAS timeout in seconds.")
    parser.add_argument("--max-workers", type=int, default=16, help="RAGAS worker concurrency.")
    parser.add_argument("--no-progress", action="store_true", help="Disable RAGAS progress output.")
    return parser.parse_args()


def load_report(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise SystemExit(
            f"RAG eval report not found: {path}. "
            "Run the Java RAG eval first to create target/rag-eval-report.json."
        )
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def report_cases(report: dict[str, Any]) -> list[dict[str, Any]]:
    cases = report.get("cases")
    if isinstance(cases, list):
        return cases
    legacy_cases = report.get("endToEnd", {}).get("cases", [])
    return legacy_cases if isinstance(legacy_cases, list) else []


def build_samples(report: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    cases = report_cases(report)
    if not cases:
        raise SystemExit("Report has no cases. Re-run Java RAGAS input generation first.")

    samples: list[dict[str, Any]] = []
    metadata: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for case in cases:
        case_id = case.get("id", "")
        question = case.get("question") or ""
        answer = case.get("answer") or ""
        reference = case.get("referenceAnswer")
        contexts = case.get("retrievedContexts")

        if reference is None or contexts is None:
            raise SystemExit(
                "Report is missing RAGAS fields 'referenceAnswer' or 'retrievedContexts'. "
                "Regenerate target/rag-eval-report.json."
            )

        contexts = [context for context in contexts if isinstance(context, str) and context.strip()]
        if not question.strip() or not answer.strip() or not str(reference).strip() or not contexts:
            skipped.append(
                {
                    "id": case_id,
                    "reason": "missing question, answer, reference, or retrieved contexts",
                    "hasQuestion": bool(question.strip()),
                    "hasAnswer": bool(answer.strip()),
                    "hasReference": bool(str(reference).strip()) if reference is not None else False,
                    "retrievedContextCount": len(contexts),
                }
            )
            continue

        samples.append(
            {
                "user_input": question,
                "response": answer,
                "retrieved_contexts": contexts,
                "reference": str(reference),
            }
        )
        metadata.append(
            {
                "id": case_id,
                "expectedIntent": case.get("expectedIntent", ""),
                "actualIntent": case.get("actualIntent", ""),
                "expectedRiskLevel": case.get("expectedRiskLevel", ""),
                "actualRiskLevel": case.get("actualRiskLevel", ""),
                "retrievedSources": case.get("retrievedSources", []),
            }
        )
    if not samples:
        raise SystemExit("No cases have all RAGAS-required fields. Check skipped cases in the Java report.")
    return samples, metadata, skipped


def build_ragas_dataset(samples: list[dict[str, Any]]) -> Any:
    try:
        import ragas  # noqa: F401
    except ImportError as exc:
        raise SystemExit(
            "RAGAS is not installed. Install it with: python3 -m pip install -r eval/requirements-ragas.txt"
        ) from exc
    try:
        from ragas import EvaluationDataset

        return EvaluationDataset.from_list(samples)
    except ImportError:
        from datasets import Dataset

        return Dataset.from_list(samples)


def find_metric_class(name: str) -> Any:
    import ragas.metrics as metrics_module

    metric_class = getattr(metrics_module, name, None)
    if metric_class is None:
        raise SystemExit(f"Installed RAGAS version does not provide metric class: {name}")
    return metric_class


def build_metrics(metric_names: str) -> list[Any]:
    names = [name.strip() for name in metric_names.split(",") if name.strip()]
    metrics = []
    for name in names:
        try:
            metrics.append(find_metric_class(name)())
        except SystemExit:
            if name == "FactualCorrectness":
                continue
            raise
    return metrics


def build_models(args: argparse.Namespace) -> tuple[Any, Any]:
    try:
        from ragas.embeddings import LangchainEmbeddingsWrapper
        from ragas.llms import LangchainLLMWrapper
    except ImportError as exc:
        raise SystemExit(
            "RAGAS model wrappers are unavailable. Check your ragas installation version."
        ) from exc

    if args.provider == "openai":
        if not os.getenv("OPENAI_API_KEY"):
            raise SystemExit("OPENAI_API_KEY is required when --provider openai is used.")
        from langchain_openai import ChatOpenAI, OpenAIEmbeddings

        judge_model = args.judge_model or "gpt-4o-mini"
        embedding_model = args.embedding_model or "text-embedding-3-small"
        llm = ChatOpenAI(model=judge_model, temperature=0)
        embeddings = OpenAIEmbeddings(model=embedding_model)
    else:
        from langchain_ollama import ChatOllama, OllamaEmbeddings

        judge_model = args.judge_model or os.getenv("OLLAMA_MODEL")
        if not judge_model:
            raise SystemExit("Set --judge-model or OLLAMA_MODEL for the optional Ollama evaluator.")
        embedding_model = args.embedding_model or "nomic-embed-text"
        llm = ChatOllama(model=judge_model, base_url=args.ollama_base_url, temperature=0)
        embeddings = OllamaEmbeddings(model=embedding_model, base_url=args.ollama_base_url)

    return LangchainLLMWrapper(llm), LangchainEmbeddingsWrapper(embeddings)


def run_ragas(args: argparse.Namespace, dataset: Any, metrics: list[Any], llm: Any, embeddings: Any) -> Any:
    from ragas import evaluate
    from ragas.run_config import RunConfig

    return evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=llm,
        embeddings=embeddings,
        run_config=RunConfig(timeout=args.timeout, max_workers=args.max_workers),
        batch_size=args.batch_size,
        show_progress=not args.no_progress,
    )


def summarize_frame(frame: Any) -> dict[str, float]:
    scores: dict[str, float] = {}
    for column in frame.columns:
        if column in INPUT_COLUMNS:
            continue
        numeric = frame[column]
        try:
            numeric = numeric.astype(float)
        except (TypeError, ValueError):
            continue
        values = [float(value) for value in numeric.tolist() if value == value]
        if values:
            scores[column] = sum(values) / len(values)
    return scores


def clean_json(value: Any) -> Any:
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return None
        return value
    if isinstance(value, dict):
        return {str(key): clean_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [clean_json(item) for item in value]
    if hasattr(value, "item"):
        return clean_json(value.item())
    return value


def write_output(
    output_path: Path,
    input_path: Path,
    report: dict[str, Any],
    scores: dict[str, float],
    cases: list[dict[str, Any]],
    skipped: list[dict[str, Any]],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "input": str(input_path),
        "sourceEvaluatedAt": report.get("evaluatedAt"),
        "dataset": report.get("dataset"),
        "topK": report.get("topK"),
        "caseCount": len(cases),
        "skippedCaseCount": len(skipped),
        "scores": scores,
        "cases": cases,
        "skippedCases": skipped,
    }
    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(clean_json(payload), handle, ensure_ascii=False, indent=2)


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output)

    report = load_report(input_path)
    samples, metadata, skipped = build_samples(report)
    dataset = build_ragas_dataset(samples)
    metrics = build_metrics(args.metrics)
    llm, embeddings = build_models(args)
    result = run_ragas(args, dataset, metrics, llm, embeddings)

    frame = result.to_pandas()
    cases = frame.to_dict(orient="records")
    for case, extra in zip(cases, metadata):
        case.update(extra)

    scores = summarize_frame(frame)
    write_output(output_path, input_path, report, scores, cases, skipped)

    print(f"RAGAS evaluation completed: {output_path}")
    if skipped:
        print(f"skipped_cases={len(skipped)}")
    for name, value in sorted(scores.items()):
        print(f"{name}={value:.4f}")


if __name__ == "__main__":
    main()
