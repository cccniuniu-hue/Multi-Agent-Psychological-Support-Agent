import unittest

from contract import ContractError, format_scores, validate_request


class ContractTests(unittest.TestCase):
    def test_accepts_batch_and_preserves_order(self):
        query, passages = validate_request(
            {"query": " question ", "passages": [" first ", "second"]}, 50)
        self.assertEqual((query, passages), ("question", ["first", "second"]))
        self.assertEqual(format_scores([0.2, -1.5], 2, "test-model"), {
            "success": True,
            "model": "test-model",
            "scores": [{"index": 0, "score": 0.2}, {"index": 1, "score": -1.5}],
        })

    def test_rejects_invalid_or_oversized_input(self):
        for payload, code in [
            ({"query": "", "passages": ["text"]}, "invalid_request"),
            ({"query": "q", "passages": []}, "invalid_request"),
            ({"query": "q", "passages": ["text", "extra"]}, "too_many_passages"),
            ({"query": "q", "passages": [""]}, "invalid_request"),
        ]:
            with self.subTest(payload=payload), self.assertRaises(ContractError) as raised:
                validate_request(payload, 1)
            self.assertEqual(raised.exception.code, code)

    def test_rejects_invalid_scores_and_accepts_single_score(self):
        self.assertEqual(format_scores(0.7, 1, "test")["scores"], [{"index": 0, "score": 0.7}])
        for scores in ([1.0], [float("nan"), 2.0]):
            with self.subTest(scores=scores), self.assertRaises(ContractError) as raised:
                format_scores(scores, 2, "test")
            self.assertEqual(raised.exception.status_code, 502)


if __name__ == "__main__":
    unittest.main()
