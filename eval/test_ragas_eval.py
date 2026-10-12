"""Dependency-free checks; no judge, embeddings or RAGAS installation required."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


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


if __name__ == "__main__":
    unittest.main()
