FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir \
    "FlagEmbedding>=1.3,<2" \
    "fastapi>=0.115,<1" \
    "uvicorn>=0.32,<1"

COPY app.py contract.py model.py service.py ./

EXPOSE 8003
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8003"]
