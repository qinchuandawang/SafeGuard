# SafeGuard 音频检测服务 Dockerfile
FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制源码
COPY src/ ./src/
COPY pretrained/ ./pretrained/

# 创建必要目录
RUN mkdir -p /app/data /app/outputs /app/tmp_uploads

EXPOSE 5000

ENV PYTHONPATH=/app \
    FLASK_DEBUG=0 \
    MODEL_DIR=/app/pretrained/asvspoof-finetuned \
    FALLBACK_MODEL=true

CMD ["python", "src/app.py"]
