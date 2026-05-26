"""
SafeGuard 音频伪造检测服务

环境变量：
    MODEL_DIR       - 模型目录（默认: outputs/wav2vec2-mid/best）
    FALLBACK_MODEL  - 若 MODEL_DIR 不存在，是否使用 pretrained/wav2vec2-base 演示 (true/false)
    SAMPLE_RATE     - 采样率（默认 16000）
    MAX_SECONDS     - 最大音频长度（秒，默认 4.0）
    PORT            - 服务端口（默认 5000）

启动命令：
    py -3.11 app.py
"""

import os
import uuid
from pathlib import Path

from flask import Flask, request

from utils import (
    load_audio,
    load_model_once,
    predict_audio,
    make_response,
    get_device,
    is_model_loaded,
)

app = Flask(__name__)

# ---- 配置 ---- #
MODEL_DIR = Path(os.getenv("MODEL_DIR", "outputs/wav2vec2-mid/best"))
FALLBACK_MODEL = os.getenv("FALLBACK_MODEL", "true").lower() == "true"
FALLBACK_MODEL_DIR = Path("pretrained/wav2vec2-base")
SAMPLE_RATE = int(os.getenv("SAMPLE_RATE", "16000"))
MAX_SECONDS = float(os.getenv("MAX_SECONDS", "4.0"))
UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", "tmp_uploads"))
PORT = int(os.getenv("PORT", "5000"))

UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

SUPPORTED_EXTENSIONS = {".wav", ".flac", ".mp3", ".m4a", ".ogg"}


def _resolve_model_dir() -> Path:
    """确定实际使用的模型目录。"""
    if MODEL_DIR.exists():
        return MODEL_DIR

    if FALLBACK_MODEL and FALLBACK_MODEL_DIR.exists():
        print(f"[WARN] MODEL_DIR 不存在 ({MODEL_DIR})，使用 fallback: {FALLBACK_MODEL_DIR}")
        print("[WARN] Fallback 模型(ForPreTraining)用于演示，准确率不如微调后的模型。")
        return FALLBACK_MODEL_DIR

    raise FileNotFoundError(
        f"模型目录不存在: {MODEL_DIR}\n"
        f"  请先执行训练: py -3.11 train_wav2vec2.py ... --output_dir {MODEL_DIR.parent}\n"
        f"  或设置环境变量 MODEL_DIR 指向有效路径。"
    )


# ================ 路由 ================ #

@app.get("/")
def index():
    return make_response(
        0,
        "audio detect service running",
        {
            "routes": ["GET /health", "POST /audio/detect"],
            "model_dir": str(MODEL_DIR),
            "device": get_device(),
            "loaded": is_model_loaded(),
        },
    )


@app.get("/health")
def health():
    request_id = str(uuid.uuid4())
    try:
        model_dir = _resolve_model_dir()
        load_model_once(model_dir)
        return make_response(
            0, "ok",
            {"device": get_device(), "model_dir": str(model_dir)},
            request_id=request_id,
        )
    except Exception as e:
        return make_response(1001, str(e), http_status=500, request_id=request_id)


@app.post("/audio/detect")
def audio_detect():
    request_id = str(uuid.uuid4())

    # 检查文件
    if "file" not in request.files:
        return make_response(4001, "缺少文件字段 file", http_status=400, request_id=request_id)

    f = request.files["file"]
    if not f.filename:
        return make_response(4002, "文件名为空", http_status=400, request_id=request_id)

    suffix = Path(f.filename).suffix.lower()
    if suffix not in SUPPORTED_EXTENSIONS:
        return make_response(
            4003, f"不支持的音频格式: {suffix}，仅支持: {', '.join(SUPPORTED_EXTENSIONS)}",
            http_status=400, request_id=request_id,
        )

    # 验证 MIME 类型（基本的安全检查，放宽对 application/octet-stream 的限制）
    if f.content_type and not f.content_type.startswith("audio/") and f.content_type != "application/octet-stream":
        return make_response(
            4004, f"非音频文件类型: {f.content_type}",
            http_status=400, request_id=request_id,
        )

    # 保存上传文件
    ts = str(int(__import__("time").time() * 1000))
    save_path = UPLOAD_DIR / f"{ts}_{Path(f.filename).name}"
    try:
        f.save(save_path)

        # 确定模型目录并加载
        model_dir = _resolve_model_dir()

        # 执行检测
        result = predict_audio(save_path, model_dir, SAMPLE_RATE, MAX_SECONDS)
        return make_response(0, "success", result, request_id=request_id)

    except FileNotFoundError as e:
        return make_response(5002, str(e), http_status=503, request_id=request_id)
    except Exception as e:
        return make_response(5001, str(e), http_status=500, request_id=request_id)
    finally:
        if save_path.exists():
            try:
                save_path.unlink()
            except Exception:
                pass


if __name__ == "__main__":
    print("=" * 50)
    print("  SafeGuard 音频检测服务")
    print(f"  模型目录: {MODEL_DIR}")
    print(f"  设备: {get_device()}")
    print(f"  采样率: {SAMPLE_RATE}")
    print(f"  端口: {PORT}")
    print("  模型将在首次请求时加载（懒加载）")
    print("=" * 50)

    app.run(host="0.0.0.0", port=PORT, debug=False)
