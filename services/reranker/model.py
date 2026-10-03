from functools import cached_property


class BgeReranker:
    def __init__(self, model_name: str):
        self.model_name = model_name

    @cached_property
    def model(self):
        from FlagEmbedding import FlagReranker

        return FlagReranker(self.model_name, use_fp16=False)

    def compute_score(self, pairs: list[list[str]]):
        return self.model.compute_score(pairs)
