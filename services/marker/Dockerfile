FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir "marker-pdf==2.0.0" "fastapi>=0.115.4" "uvicorn>=0.32.0" "python-multipart>=0.0.16"

EXPOSE 8001
CMD ["marker_server", "--host", "0.0.0.0", "--port", "8001"]
