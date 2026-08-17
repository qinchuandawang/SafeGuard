import os
from dataclasses import dataclass
from pathlib import Path


def _as_float(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)))


@dataclass(frozen=True)
class Settings:
    """从环境变量加载编排服务配置。"""

    audio_url: str = os.getenv("AUDIO_DETECTION_URL", "http://localhost:5000/audio/detect")
    video_url: str = os.getenv("VIDEO_DETECTION_URL", "http://localhost:5002/api/detect/video")
    text_url: str = os.getenv(
        "TEXT_DETECTION_URL", "http://localhost:8080/api/internal/llm/text-detection"
    )
    audio_fallback_model: str = os.getenv("AUDIO_FALLBACK_MODEL", "")
    video_fallback_model: str = os.getenv("VIDEO_FALLBACK_MODEL", "")
    internal_token: str = os.getenv("ORCHESTRATOR_INTERNAL_TOKEN", "")
    storage_dir: Path = Path(os.getenv("ORCHESTRATOR_STORAGE_DIR", "./data"))
    request_timeout_seconds: float = _as_float("ORCHESTRATOR_REQUEST_TIMEOUT_SECONDS", 300.0)
    max_upload_mb: int = int(os.getenv("ORCHESTRATOR_MAX_UPLOAD_MB", "200"))
    max_concurrent_workflows: int = int(os.getenv("ORCHESTRATOR_MAX_CONCURRENT", "4"))
    fallback_confidence_threshold: float = _as_float("FALLBACK_CONFIDENCE_THRESHOLD", 0.45)
    conflict_confidence_threshold: float = _as_float("CONFLICT_CONFIDENCE_THRESHOLD", 0.60)
    audio_weight: float = _as_float("AUDIO_FUSION_WEIGHT", 0.35)
    video_weight: float = _as_float("VIDEO_FUSION_WEIGHT", 0.40)
    text_weight: float = _as_float("TEXT_FUSION_WEIGHT", 0.25)

    def prepare(self) -> None:
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        (self.storage_dir / "uploads").mkdir(parents=True, exist_ok=True)


settings = Settings()
