import unittest

from service import score_passages


class FakeReranker:
    model_name = "test-reranker"

    def compute_score(self, pairs):
        self.pairs = pairs
        return [0.4, 1.2]


class ServiceTests(unittest.TestCase):
    def test_scores_every_query_passage_pair_in_request_order(self):
        client = FakeReranker()
        result = score_passages(client, "question", ["first", "second"])

        self.assertEqual(client.pairs, [["question", "first"], ["question", "second"]])
        self.assertEqual(result["scores"], [
            {"index": 0, "score": 0.4},
            {"index": 1, "score": 1.2},
        ])
        self.assertEqual(result["model"], "test-reranker")


if __name__ == "__main__":
    unittest.main()
