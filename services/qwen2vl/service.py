from typing import Protocol

from contract import Settings, parse_model_output, validate_image


class VisionClient(Protocol):
    model_name: str

    def describe(self, image: bytes, content_type: str) -> str:
        ...


def describe_image(client: VisionClient, filename: str | None, content_type: str | None,
                   image: bytes, settings: Settings) -> dict:
    source, normalized_type = validate_image(
        filename, content_type, len(image), settings.max_image_bytes)
    return {
        "success": True,
        "source": source,
        "model": client.model_name,
        "description": parse_model_output(client.describe(image, normalized_type)),
    }
