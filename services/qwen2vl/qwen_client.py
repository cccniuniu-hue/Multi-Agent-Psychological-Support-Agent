import io
from functools import lru_cache

from contract import Settings


PROMPT = """
分析这张图片，只返回一个 JSON 对象，不要添加 Markdown 或解释。字段必须为：
image_type：图片或图表类型；
summary：一段可独立检索的中文摘要；
core_elements：核心元素字符串数组；
key_relations：关键关系字符串数组；
data_insights：能从图片直接读出的数据结论字符串数组，没有则返回空数组。
不要猜测图片中不存在的信息。
""".strip()


class Qwen2VlClient:
    def __init__(self, settings: Settings):
        self.model_name = settings.model
        self.max_new_tokens = settings.max_new_tokens

    @lru_cache(maxsize=1)
    def _load(self):
        from transformers import AutoProcessor, Qwen2VLForConditionalGeneration

        model = Qwen2VLForConditionalGeneration.from_pretrained(
            self.model_name, torch_dtype="auto", device_map="auto")
        processor = AutoProcessor.from_pretrained(self.model_name)
        return model, processor

    def describe(self, image: bytes, _content_type: str) -> str:
        from PIL import Image

        model, processor = self._load()
        picture = Image.open(io.BytesIO(image)).convert("RGB")
        messages = [{
            "role": "user",
            "content": [
                {"type": "image", "image": picture},
                {"type": "text", "text": PROMPT},
            ],
        }]
        prompt = processor.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True)
        inputs = processor(
            text=[prompt], images=[picture], padding=True, return_tensors="pt").to(model.device)
        generated = model.generate(**inputs, max_new_tokens=self.max_new_tokens)
        trimmed = [output[len(input_ids):] for input_ids, output in zip(inputs.input_ids, generated)]
        return processor.batch_decode(
            trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False)[0]
