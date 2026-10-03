from contract import format_scores


def score_passages(client, query: str, passages: list[str]) -> dict:
    pairs = [[query, passage] for passage in passages]
    return format_scores(client.compute_score(pairs), len(passages), client.model_name)
