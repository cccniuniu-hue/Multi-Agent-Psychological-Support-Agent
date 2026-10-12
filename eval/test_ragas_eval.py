"""Dependency-free checks; no judge, embeddings or RAGAS installation required."""

import copy
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("run-ragas-eval.py")
SPEC = importlib.util.spec_from_file_location("ragas_eval", SCRIPT)
ragas_eval = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ragas_eval)


def example_case():
    return {
        "id": "synthetic-test",
        "question": "示例资料建议如何拆分复习任务？",
        "answer": "把复习任务分成小块，并安排休息。",
        "referenceAnswer": "拆分任务并安排短时休息。",
        "retrievedContexts": ["示例资料：把任务分成小块，安排短时休息。"],
        "retrievedSources": ["synthetic-guide.md"],
    }


class InputChecks(unittest.TestCase):
    def test_rejects_context_string_instead_of_splitting_it_into_characters(self):
        case = example_case()
        case["retrievedContexts"] = "this is not a list"
        with self.assertRaisesRegex(SystemExit, "retrievedContexts"):
            ragas_eval.build_samples({"cases": [case]})

    def test_validates_types_and_unique_ids(self):
        for field, value in [("answer", 42), ("referenceAnswer", {}), ("retrievedContexts", [None])]:
            with self.subTest(field=field):
                case = example_case()
                case[field] = value
                with self.assertRaises(SystemExit):
                    ragas_eval.build_samples({"cases": [case]})
        case = example_case()
        with self.assertRaisesRegex(SystemExit, "duplicate"):
            ragas_eval.build_samples({"cases": [case, copy.deepcopy(case)]})
        with self.assertRaises(SystemExit):
            ragas_eval.build_samples({"cases": ["not an object"]})

    def test_keeps_metadata_aligned_and_reports_empty_context_cases(self):
        empty = example_case()
        empty.update(id="synthetic-no-context", retrievedContexts=[])
        samples, metadata, skipped = ragas_eval.build_samples({"cases": [empty, example_case()]})
        self.assertEqual(len(samples), 1)
        self.assertEqual(metadata[0]["id"], "synthetic-test")
        self.assertEqual(skipped[0]["id"], "synthetic-no-context")
        self.assertEqual(skipped[0]["retrievedContextCount"], 0)
        legacy = ragas_eval.build_samples({"endToEnd": {"cases": [example_case()]}})
        self.assertEqual(legacy[0], samples)

    def test_reports_invalid_json_and_supports_windows_utf8_bom(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(json.dumps({"cases": [example_case()]}), encoding="utf-8-sig")
            self.assertEqual(len(ragas_eval.load_report(path)["cases"]), 1)
            for content in ["{broken", "[]", '{"value": NaN}']:
                with self.subTest(content=content):
                    path.write_text(content, encoding="utf-8")
                    with self.assertRaises(SystemExit):
                        ragas_eval.load_report(path)

    def test_validate_only_cli_needs_no_services_and_writes_no_scores(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "input.json"
            output_path = Path(directory) / "output.json"
            input_path.write_text(json.dumps({"cases": [example_case()]}), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--input", str(input_path), "--output", str(output_path), "--validate-only"],
                capture_output=True, text=True, encoding="utf-8",
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual(summary["status"], "validated")
            self.assertEqual(summary["eligibleCaseCount"], 1)
            self.assertNotIn("scores", summary)
            self.assertFalse(output_path.exists())

    def test_rejects_invalid_case_container(self):
        for report in [{"cases": {}}, {"endToEnd": []}, {"cases": []}]:
            with self.subTest(report=report), self.assertRaises(SystemExit):
                ragas_eval.build_samples(report)


class ScoreChecks(unittest.TestCase):
    def run_fixture(self, rows, *, skipped=False, metric="Faithfulness", metric_name="faithfulness"):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "input.json"
            output_path = Path(directory) / "output.json"
            case = example_case()
            second = dict(example_case(), id="synthetic-second")
            cases = [case, second]
            if skipped:
                cases.append(dict(example_case(), id="synthetic-skipped", retrievedContexts=[]))
            input_path.write_text(json.dumps({"fusionStrategy": "RRF", "topK": 2, "cases": cases}), encoding="utf-8")
            argv = [str(SCRIPT), "--input", str(input_path), "--output", str(output_path), "--metrics", metric]
            with patch.object(sys, "argv", argv), \
                    patch.object(ragas_eval, "build_ragas_dataset", return_value="dataset"), \
                    patch.object(ragas_eval, "build_metrics", return_value=[SimpleNamespace(name=metric_name)]), \
                    patch.object(ragas_eval, "build_models", return_value=("judge", "embeddings")), \
                    patch.object(ragas_eval, "run_ragas", return_value=SimpleNamespace(scores=rows)), \
                    patch("importlib.metadata.version", return_value="0.3.0-test"), \
                    redirect_stdout(io.StringIO()):
                error = None
                try:
                    ragas_eval.main()
                except SystemExit as exc:
                    error = exc
            payload = json.loads(output_path.read_text(encoding="utf-8")) if output_path.exists() else None
            return payload, error

    def test_partial_report_counts_invalid_scores_without_inflating_the_mean(self):
        payload, error = self.run_fixture([{"faithfulness": 0.8}, {"faithfulness": float("inf")}], skipped=True)
        self.assertIsNone(error)
        self.assertEqual(payload["status"], "partial")
        self.assertEqual(payload["scores"]["faithfulness"], 0.8)
        self.assertEqual(payload["metricCoverage"]["faithfulness"], {"validCount": 1, "failedCount": 1})
        self.assertIsNone(payload["cases"][1]["faithfulness"])
        self.assertEqual(payload["cases"][1]["id"], "synthetic-second")
        self.assertEqual(payload["inputCaseCount"], 3)
        self.assertEqual(payload["skippedCaseCount"], 1)
        self.assertEqual(payload["fusionStrategy"], "RRF")
        self.assertEqual(payload["evaluation"]["metrics"], ["Faithfulness"])
        self.assertEqual(payload["evaluation"]["ragasVersion"], "0.3.0-test")
        self.assertEqual(payload["evaluation"]["judgeModel"], "gpt-4o-mini")
        self.assertEqual(payload["evaluation"]["embeddingModel"], "text-embedding-3-small")

    def test_all_failed_scores_write_null_and_return_failure(self):
        payload, error = self.run_fixture([{"faithfulness": float("nan")}, {"faithfulness": None}])
        self.assertIsNotNone(error)
        self.assertEqual(payload["status"], "failed")
        self.assertIsNone(payload["scores"]["faithfulness"])
        self.assertEqual(payload["metricCoverage"]["faithfulness"]["failedCount"], 2)

    def test_complete_report_preserves_real_zero_scores(self):
        payload, error = self.run_fixture([{"faithfulness": 0.0}, {"faithfulness": 1.0}])
        self.assertIsNone(error)
        self.assertEqual(payload["status"], "complete")
        self.assertEqual(payload["scores"]["faithfulness"], 0.5)
        self.assertEqual(payload["metricCoverage"]["faithfulness"]["validCount"], 2)

    def test_result_count_or_metric_mismatch_never_writes_misaligned_report(self):
        for rows in [[{"faithfulness": 1.0}], [{"unexpected": 1.0}, {"unexpected": 1.0}]]:
            with self.subTest(rows=rows):
                payload, error = self.run_fixture(rows)
                self.assertIsNotNone(error)
                self.assertIsNone(payload)

    def test_factual_correctness_mode_column_stays_addressable(self):
        column = "factual_correctness(mode=f1)"
        payload, error = self.run_fixture(
            [{column: 0.4}, {column: 1.0}],
            metric="FactualCorrectness", metric_name="factual_correctness",
        )
        self.assertIsNone(error)
        self.assertEqual(payload["status"], "complete")
        self.assertAlmostEqual(payload["scores"][column], 0.7)

    def test_requested_metric_is_never_silently_skipped(self):
        with patch.object(ragas_eval, "find_metric_class", side_effect=SystemExit("missing metric")):
            with self.assertRaises(SystemExit):
                ragas_eval.build_metrics(["FactualCorrectness"])

    def test_default_metrics_and_positive_cli_limits(self):
        with patch.object(sys, "argv", [str(SCRIPT)]):
            args = ragas_eval.parse_args()
        self.assertEqual(set(args.metrics), {
            "Faithfulness", "ResponseRelevancy", "LLMContextPrecisionWithReference", "LLMContextRecall",
        })
        for option, value in [("--timeout", "0"), ("--batch-size", "-1"), ("--max-workers", "0"),
                              ("--metrics", ""), ("--metrics", "Faithfulness,Faithfulness")]:
            with self.subTest(option=option), patch.object(sys, "argv", [str(SCRIPT), option, value]), \
                    patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                ragas_eval.parse_args()

    def test_input_report_cannot_be_overwritten_by_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            content = json.dumps({"cases": [example_case()]})
            path.write_text(content, encoding="utf-8")
            with patch.object(sys, "argv", [str(SCRIPT), "--input", str(path), "--output", str(path)]), \
                    self.assertRaisesRegex(SystemExit, "different"):
                ragas_eval.main()
            self.assertEqual(path.read_text(encoding="utf-8"), content)


if __name__ == "__main__":
    unittest.main()
