"""
SafeGuard 音频训练模块 - 公共工具函数

集中管理音频加载、模型加载等共用逻辑，避免代码重复。
"""

import threading
import time
import os
from pathlib import Path
from typing import Optional, Tuple

import numpy as np
import soundfile as sf
import torch
from transformers import Wav2Vec2ForSequenceClassification, Wav2Vec2Processor

# ======================== 全局变量 ========================

PROCESSOR: Optional[Wav2Vec2Processor] = None
MODEL: Optional[Wav2Vec2ForSequenceClassification] = None
DEVICE: str = "cuda" if torch.cuda.is_available() else "cpu"

_model_lock = threading.Lock()
_model_loaded = False

# ======================== 音频加载 ========================

def load_audio(
    path: Path,
    target_sr: int = 16000,
    max_seconds: float = 4.0,
    method: str = "linear"
) -> np.ndarray:
    """
    加载音频文件，重采样到 target_sr，截断到 max_seconds。

    Args:
        path: 音频文件路径
        target_sr: 目标采样率（默认 16000）
        max_seconds: 最大时长（秒），超过则截断
        method: 重采样方法，"linear"（线性插值）或 "fft"（FFT 重采样）

    Returns:
        shape=(samples,) 的 float32 数组，值域 [-1, 1]
    """
    if not path.exists():
        raise FileNotFoundError(f"音频文件不存在: {path}")

    wav, sr = sf.read(path)

    # 多声道 -> 单声道
    if wav.ndim > 1:
        wav = wav.mean(axis=1)
    wav = wav.astype(np.float32)

    # 重采样到目标采样率
    if sr != target_sr:
        duration = len(wav) / sr
        target_len = int(duration * target_sr)
        if target_len > 1:
            if method == "fft":
                wav = _resample_fft(wav, target_len)
            else:
                wav = _resample_linear(wav, sr, target_sr)
        wav = wav.astype(np.float32)

    # 截断到最大长度
    max_len = int(max_seconds * target_sr)
    if len(wav) > max_len:
        wav = wav[:max_len]

    return wav


def _resample_linear(wav: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    """线性插值重采样（快速，但可能有 aliasing）。"""
    duration = len(wav) / orig_sr
    target_len = int(duration * target_sr)
    x_old = np.linspace(0, 1, num=len(wav), endpoint=False)
    x_new = np.linspace(0, 1, num=target_len, endpoint=False)
    return np.interp(x_new, x_old, wav)


def _resample_fft(wav: np.ndarray, target_len: int) -> np.ndarray:
    """FFT 重采样（质量更高，适合音频）。"""
    try:
        from scipy.signal import resample
        return resample(wav, target_len)
    except ImportError:
        # scipy 未安装，降级到线性插值
        x_old = np.linspace(0, 1, num=len(wav), endpoint=False)
        x_new = np.linspace(0, 1, num=target_len, endpoint=False)
        return np.interp(x_new, x_old, wav)


# ======================== 模型加载 ========================

def load_model_once(model_dir: Path) -> Tuple[Wav2Vec2Processor, Wav2Vec2ForSequenceClassification]:
    """
    线程安全的单次模型加载（双重检查锁定）。

    Returns:
        (processor, model)
    """
    global PROCESSOR, MODEL, _model_loaded

    if _model_loaded:
        return PROCESSOR, MODEL

    with _model_lock:
        if _model_loaded:
            return PROCESSOR, MODEL

        if not model_dir.exists():
            raise FileNotFoundError(
                f"模型目录不存在: {model_dir}\n"
                f"请先训练模型或指定正确的路径。"
            )

        t0 = time.time()
        print(f"[加载模型] 从 {model_dir} 加载 Wav2Vec2...")

        PROCESSOR = Wav2Vec2Processor.from_pretrained(str(model_dir))
        MODEL = Wav2Vec2ForSequenceClassification.from_pretrained(
            str(model_dir),
            num_labels=2,
            ignore_mismatched_sizes=True,
        ).to(DEVICE)
        MODEL.eval()
        # 半精度推理（需确保 forward 返回的 logits 能被正确计算）
        # 注：Wav2Vec2 部分算子不支持 half，跳过

        elapsed = time.time() - t0
        print(f"[加载完成] 耗时 {elapsed:.1f}s, 设备: {DEVICE}")

        _model_loaded = True
        return PROCESSOR, MODEL


def get_device() -> str:
    """获取当前设备。"""
    return DEVICE


def is_model_loaded() -> bool:
    """检查模型是否已加载（线程安全）。"""
    return _model_loaded


# ======================== 推理函数 ========================

def predict_audio(
    audio_path: Path,
    model_dir: Path,
    sample_rate: int = 16000,
    max_seconds: float = 4.0,
) -> dict:
    """
    对单条音频进行伪造检测。

    Returns:
        包含 label, spoof_prob, bonafide_prob, confidence, risk_level,
        latency_ms, model_version 等字段的 dict。
    """
    processor, model = load_model_once(model_dir)
    wav = load_audio(audio_path, target_sr=sample_rate, max_seconds=max_seconds)

    inputs = processor(
        wav, sampling_rate=sample_rate,
        return_tensors="pt", padding=True
    )
    inputs = {k: v.to(DEVICE) for k, v in inputs.items()}

    start = time.time()
    with torch.no_grad():
        logits = model(**inputs).logits
        probs = torch.softmax(logits, dim=-1).cpu().numpy()[0]
    latency_ms = (time.time() - start) * 1000

    pred_id = int(np.argmax(probs))
    label = "bonafide" if pred_id == 0 else "spoof"
    spoof_prob = float(probs[1])

    return {
        "label": label,
        "spoof_prob": spoof_prob,
        "bonafide_prob": float(probs[0]),
        "confidence": float(probs[pred_id]),
        "risk_level": _risk_level(spoof_prob),
        "latency_ms": round(latency_ms, 2),
        "model_version": str(model_dir),
        "device": DEVICE,
        "sample_rate": sample_rate,
        "max_seconds": max_seconds,
    }


def _risk_level(spoof_prob: float) -> str:
    """根据伪造概率确定风险等级。"""
    if spoof_prob >= 0.85:
        return "high"
    if spoof_prob >= 0.5:
        return "medium"
    return "low"


def make_response(code: int, message: str, data=None, http_status: int = 200, request_id: str = ""):
    """构建统一响应格式。"""
    from flask import jsonify
    payload = {
        "code": code,
        "message": message,
        "request_id": request_id,
    }
    if data is not None:
        payload["data"] = data
    return jsonify(payload), http_status
