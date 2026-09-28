FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir \
    "torch>=2.4,<3" \
    "transformers>=4.45,<5" \
    "accelerate>=0.34,<2" \
    "Pillow>=10,<13" \
    "fastapi>=0.115,<1" \
    "uvicorn>=0.32,<1" \
    "python-multipart>=0.0.16,<1"

COPY app.py contract.py qwen_client.py service.py ./

EXPOSE 8002
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8002"]
