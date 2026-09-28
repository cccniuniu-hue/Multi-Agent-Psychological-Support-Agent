import os
import unittest
from unittest.mock import patch

from contract import ContractError, load_settings, parse_model_output, validate_image


class ContractTests(unittest.TestCase):
    def test_accepts_supported_image_and_sanitizes_source(self):
        self.assertEqual(
            validate_image("folder/chart.PNG", "image/png", 128, 1024),
            ("chart.PNG", "image/png"),
        )

    def test_rejects_unsupported_and_oversized_images(self):
        with self.assertRaisesRegex(ContractError, "unsupported_image_type"):
            validate_image("chart.gif", "image/gif", 128, 1024)
        with self.assertRaisesRegex(ContractError, "image_too_large"):
            validate_image("chart.png", "image/png", 2048, 1024)

    def test_parses_structured_model_output(self):
        result = parse_model_output("""
                ```json
                {
                  "image_type": "bar_chart",
                  "summary": "焦虑评分在考试周上升。",
                  "core_elements": ["考试周", "焦虑评分"],
                  "key_relations": ["考试周与评分上升同时出现"],
                  "data_insights": ["最高值为 8 分"]
                }
                ```
                """)

        self.assertEqual(result["image_type"], "bar_chart")
        self.assertEqual(result["summary"], "焦虑评分在考试周上升。")
        self.assertEqual(result["core_elements"], ["考试周", "焦虑评分"])
        self.assertEqual(result["key_relations"], ["考试周与评分上升同时出现"])
        self.assertEqual(result["data_insights"], ["最高值为 8 分"])

    def test_rejects_model_output_without_summary(self):
        with self.assertRaisesRegex(ContractError, "invalid_model_response"):
            parse_model_output('{"image_type":"photo"}')

    def test_loads_limits_and_model_from_environment(self):
        with patch.dict(os.environ, {
            "QWEN2VL_MODEL": "test-model",
            "QWEN2VL_MAX_IMAGE_BYTES": "2048",
            "QWEN2VL_TIMEOUT_SECONDS": "30",
            "QWEN2VL_MAX_NEW_TOKENS": "256",
        }):
            settings = load_settings()

        self.assertEqual(settings.model, "test-model")
        self.assertEqual(settings.max_image_bytes, 2048)
        self.assertEqual(settings.timeout_seconds, 30.0)
        self.assertEqual(settings.max_new_tokens, 256)


if __name__ == "__main__":
    unittest.main()
