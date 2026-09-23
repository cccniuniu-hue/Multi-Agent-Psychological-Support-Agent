FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir "marker-pdf[full]==2.0.0" "fastapi>=0.115.4" "uvicorn>=0.32.0" "python-multipart>=0.0.16"

COPY app.py contract.py ./

EXPOSE 8001
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
