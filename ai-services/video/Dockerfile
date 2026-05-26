# SafeGuard 视频检测服务 Dockerfile
FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖（OpenCV + face_recognition 需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1-mesa-glx \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender-dev \
    libgomp1 \
    cmake \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制源码
COPY api/ ./api/
COPY models/ ./models/
COPY utils/ ./utils/
COPY config.yaml .

# 创建必要目录
RUN mkdir -p /app/data /app/checkpoints /app/tmp_uploads

EXPOSE 5002

ENV PYTHONPATH=/app \
    FLASK_DEBUG=0 \
    CUDA_VISIBLE_DEVICES=""

CMD ["python", "api/app.py"]
