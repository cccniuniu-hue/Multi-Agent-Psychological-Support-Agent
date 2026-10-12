#!/usr/bin/env python3
"""Run RAGAS metrics over the MindBridge RAG evaluation report."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from importlib import metadata as package_metadata
import json
import math
import os
from pathlib import Path
from typing import Any


DEFAULT_METRICS = "Faithfulness,ResponseRelevancy,LLMContextPrecisionWithReference,LLMContextRecall"


def positive_int(value: str) -> int:
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def metric_names(value: str) -> list[str]:
    names = [name.strip() for name in value.split(",") if name.strip()]
    if not names or len(names) != len(set(names)):
        raise argparse.ArgumentTypeError("--metrics must contain distinct, non-empty metric class names")
    return names


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAGAS over target/rag-eval-report.json.")
    parser.add_argument("--input", default="target/rag-eval-report.json", help="Java RAG eval report path.")
    parser.add_argument("--output", default="target/ragas-report.json", help="RAGAS JSON report path.")
    parser.add_argument("--provider", choices=["openai", "ollama"], default="openai", help="Evaluator model provider.")
    parser.add_argument("--judge-model", default=None, help="LLM-as-judge model name.")
    parser.add_argument("--embedding-model", default=None, help="Embedding model name.")
    parser.add_argument("--ollama-base-url", default=os.getenv("OLLAMA_BASE_URL", "http://localhost:11434"))
    parser.add_argument("--batch-size", type=positive_int, default=None)
    parser.add_argument(
        "--metrics",
        type=metric_names,
        default=DEFAULT_METRICS,
        help="Comma-separated RAGAS metric class names to run.",
    )
    parser.add_argument("--timeout", type=positive_int, default=180, help="Per-job RAGAS timeout in seconds.")
    parser.add_argument("--max-workers", type=positive_int, default=16, help="RAGAS worker concurrency.")
    parser.add_argument("--no-progress", action="store_true", help="Disable RAGAS progress output.")
    parser.add_argument("--validate-only", action="store_true", help="Validate input without importing RAGAS or calling models; write no scores.")
    return parser.parse_args()


def reject_json_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON number: {value}")


def load_report(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise SystemExit(
            f"RAG eval report not found: {path}. "
            "Run the Java RAG eval first to create target/rag-eval-report.json."
        )
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            report = json.load(handle, parse_constant=reject_json_constant)
    except (OSError, UnicodeError, ValueError) as exc:
        raise SystemExit(f"Cannot read RAG eval report {path}: {exc}") from exc
    if not isinstance(report, dict):
        raise SystemExit("RAG eval report must be a JSON object containing cases.")
    return report


def report_cases(report: dict[str, Any]) -> list[dict[str, Any]]:
    if "cases" in report:
        cases = report["cases"]
    else:
        legacy = report.get("endToEnd", {})
        if not isinstance(legacy, dict):
            raise SystemExit("Report endToEnd must be an object containing cases.")
        cases = legacy.get("cases", [])
    if not isinstance(cases, list):
        raise SystemExit("Report cases must be a JSON array.")
    return cases


def build_samples(report: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    cases = report_cases(report)
    if not cases:
        raise SystemExit("Report has no cases. Re-run Java RAGAS input generation first.")

    samples: list[dict[str, Any]] = []
    metadata: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            raise SystemExit("Each report case must be a JSON object.")
        case_id = case.get("id", "")
        if not isinstance(case_id, str) or not case_id.strip():
            raise SystemExit("Each report case must have a non-empty string id.")
        if case_id in seen_ids:
            raise SystemExit(f"Report contains duplicate case id: {case_id}")
        seen_ids.add(case_id)
        question = case.get("question", "")
        answer = case.get("answer", "")
        reference = case.get("referenceAnswer")
        contexts = case.get("retrievedContexts")

        if reference is None or contexts is None:
            raise SystemExit(
                "Report is missing RAGAS fields 'referenceAnswer' or 'retrievedContexts'. "
                "Regenerate target/rag-eval-report.json."
            )

        for field, value in [("question", question), ("answer", answer), ("referenceAnswer", reference)]:
            if not isinstance(value, str):
                raise SystemExit(f"Case {case_id}: {field} must be a string.")
        if not isinstance(contexts, list) or any(not isinstance(context, str) for context in contexts):
            raise SystemExit(f"Case {case_id}: retrievedContexts must be a list of strings.")
        contexts = [context for context in contexts if context.strip()]
        if not question.strip() or not answer.strip() or not reference.strip() or not contexts:
            skipped.append(
                {
                    "id": case_id,
                    "reason": "missing question, answer, reference, or retrieved contexts",
                    "hasQuestion": bool(question.strip()),
                    "hasAnswer": bool(answer.strip()),
                    "hasReference": bool(reference.strip()),
                    "retrievedContextCount": len(contexts),
                }
            )
            continue

        samples.append(
            {
                "user_input": question,
                "response": answer,
                "retrieved_contexts": contexts,
                "reference": reference,
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
        raise SystemExit(f"No cases have all RAGAS-required fields; skipped={len(skipped)}. Check question, answer, reference and contexts.")
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


def build_metrics(names: list[str]) -> list[Any]:
    return [find_metric_class(name)() for name in names]


def evaluator_model_names(args: argparse.Namespace) -> tuple[str, str]:
    if args.provider == "openai":
        return args.judge_model or "gpt-4o-mini", args.embedding_model or "text-embedding-3-small"
    judge_model = args.judge_model or os.getenv("OLLAMA_MODEL")
    if not judge_model:
        raise SystemExit("Set --judge-model or OLLAMA_MODEL for the optional Ollama evaluator.")
    return judge_model, args.embedding_model or "nomic-embed-text"


def build_models(args: argparse.Namespace) -> tuple[Any, Any]:
    judge_model, embedding_model = evaluator_model_names(args)
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

        llm = ChatOpenAI(model=judge_model, temperature=0)
        embeddings = OpenAIEmbeddings(model=embedding_model)
    else:
        from langchain_ollama import ChatOllama, OllamaEmbeddings

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
        raise_exceptions=False,
    )


def finite_score(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) else None


def summarize_scores(cases: list[dict[str, Any]], columns: list[str]) -> tuple[dict[str, Any], dict[str, Any]]:
    scores = {}
    coverage = {}
    for column in columns:
        values = [case[column] for case in cases if case[column] is not None]
        scores[column] = sum(values) / len(values) if values else None
        coverage[column] = {"validCount": len(values), "failedCount": len(cases) - len(values)}
    return scores, coverage


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
    scores: dict[str, Any],
    cases: list[dict[str, Any]],
    skipped: list[dict[str, Any]],
    evaluation: dict[str, Any],
    coverage: dict[str, Any],
    status: str,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "input": str(input_path),
        "sourceEvaluatedAt": report.get("evaluatedAt"),
        "dataset": report.get("dataset"),
        "fusionStrategy": report.get("fusionStrategy"),
        "topK": report.get("topK"),
        "status": status,
        "evaluation": evaluation,
        "inputCaseCount": len(cases) + len(skipped),
        "caseCount": len(cases),
        "skippedCaseCount": len(skipped),
        "scores": scores,
        "metricCoverage": coverage,
        "cases": cases,
        "skippedCases": skipped,
    }
    text = json.dumps(clean_json(payload), ensure_ascii=False, indent=2, allow_nan=False)
    output_path.write_text(text, encoding="utf-8")


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output)

    report = load_report(input_path)
    samples, metadata, skipped = build_samples(report)
    if args.validate_only:
        print(json.dumps({
            "status": "validated",
            "inputCaseCount": len(samples) + len(skipped),
            "eligibleCaseCount": len(samples),
            "skippedCaseCount": len(skipped),
            "skippedCases": skipped,
        }, ensure_ascii=False, indent=2))
        return
    if input_path.resolve() == output_path.resolve():
        raise SystemExit("Input and output paths must be different; do not overwrite the Java report.")
    dataset = build_ragas_dataset(samples)
    metrics = build_metrics(args.metrics)
    llm, embeddings = build_models(args)
    result = run_ragas(args, dataset, metrics, llm, embeddings)

    rows = result.scores
    if not isinstance(rows, list) or len(rows) != len(samples) or any(not isinstance(row, dict) for row in rows):
        raise SystemExit("RAGAS result count or shape does not match the input; cannot align case IDs.")
    returned_columns = {column for row in rows for column in row}
    columns = []
    for metric in metrics:
        matches = [column for column in returned_columns
                   if column == metric.name or column.startswith(metric.name + "(mode=")]
        if len(matches) != 1:
            raise SystemExit(f"RAGAS result is missing or has ambiguous scores for metric: {metric.name}")
        columns.append(matches[0])
    cases = [{**sample, **extra, **{column: finite_score(row.get(column)) for column in columns}}
             for sample, extra, row in zip(samples, metadata, rows)]
    scores, coverage = summarize_scores(cases, columns)
    status = "failed" if all(value is None for value in scores.values()) else (
        "partial" if skipped or any(item["failedCount"] for item in coverage.values()) else "complete")
    judge_model, embedding_model = evaluator_model_names(args)
    evaluation = {
        "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        "provider": args.provider,
        "judgeModel": judge_model,
        "embeddingModel": embedding_model,
        "metrics": args.metrics,
        "ragasVersion": package_metadata.version("ragas"),
        "timeoutSeconds": args.timeout,
        "maxWorkers": args.max_workers,
        "batchSize": args.batch_size,
    }
    write_output(output_path, input_path, report, scores, cases, skipped, evaluation, coverage, status)

    print(f"RAGAS evaluation {status}: {output_path}")
    if skipped:
        print(f"skipped_cases={len(skipped)}")
    for name, value in sorted(scores.items()):
        print(f"{name}={value:.4f}" if value is not None else f"{name}=null (no valid scores)")
    if status == "failed":
        raise SystemExit("No finite metric scores were produced; failure details are recorded in the report.")


if __name__ == "__main__":
    main()
