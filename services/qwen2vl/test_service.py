import unittest

from contract import Settings
from service import describe_image


class MockVisionClient:
    model_name = "mock-qwen2-vl"

    def describe(self, image: bytes, content_type: str) -> str:
        if image != b"png-data" or content_type != "image/png":
            raise AssertionError("unexpected image input")
        return """
        {
          "image_type": "line_chart",
          "summary": "睡眠时长逐周增加。",
          "core_elements": ["周次", "睡眠时长"],
          "key_relations": ["两者呈上升趋势"],
          "data_insights": ["第四周达到最高值"]
        }
        """


class ServiceTests(unittest.TestCase):
    def test_mock_client_produces_http_response_contract(self):
        response = describe_image(
            MockVisionClient(),
            "charts/sleep.png",
            "image/png",
            b"png-data",
            Settings("unused", 1024, 30.0, 256),
        )

        self.assertEqual(response, {
            "success": True,
            "source": "sleep.png",
            "model": "mock-qwen2-vl",
            "description": {
                "image_type": "line_chart",
                "summary": "睡眠时长逐周增加。",
                "core_elements": ["周次", "睡眠时长"],
                "key_relations": ["两者呈上升趋势"],
                "data_insights": ["第四周达到最高值"],
            },
        })


if __name__ == "__main__":
    unittest.main()
