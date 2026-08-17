from pathlib import Path
from typing import Any

import httpx

from .config import Settings


class DetectionClient:
    """调用文本、音频和视频检测服务，并统一提取模型概率。"""

    def __init__(self, config: Settings):
        self.config = config

    def detect_audio(self, path: str, model_id: str, task_id: str, trace_id: str) -> dict[str, Any]:
        return self._post_file(self.config.audio_url, path, model_id, task_id, trace_id)

    def detect_video(self, path: str, model_id: str, task_id: str, trace_id: str) -> dict[str, Any]:
        return self._post_file(self.config.video_url, path, model_id, task_id, trace_id)

    def detect_text(self, text: str, task_id: str, trace_id: str) -> dict[str, Any]:
        headers = {"X-Task-Id": task_id, "X-Trace-Id": trace_id}
        if self.config.internal_token:
            headers["Authorization"] = f"Bearer {self.config.internal_token}"
        response = httpx.post(
            self.config.text_url,
            json={"text": text, "taskId": task_id, "traceId": trace_id},
            headers=headers,
            timeout=self.config.request_timeout_seconds,
        )
        response.raise_for_status()
        return self._extract_data(response.json())

    def _post_file(self, url: str, path: str, model_id: str, task_id: str, trace_id: str) -> dict[str, Any]:
        file_path = Path(path)
        form = {"task_id": task_id}
        if model_id:
            form["model_id"] = model_id
        headers = {"X-Task-Id": task_id, "X-Trace-Id": trace_id}
        with file_path.open("rb") as media:
            response = httpx.post(
                url,
                data=form,
                files={"file": (file_path.name, media, "application/octet-stream")},
                headers=headers,
                timeout=self.config.request_timeout_seconds,
            )
        response.raise_for_status()
        return self._extract_data(response.json())

    @staticmethod
    def _extract_data(payload: dict[str, Any]) -> dict[str, Any]:
        if payload.get("code", 0) not in (0, 200) or payload.get("success") is False:
            raise RuntimeError(str(payload.get("message") or payload.get("error") or "推理服务返回失败"))
        data = payload.get("data")
        if not isinstance(data, dict):
            raise RuntimeError("推理服务未返回有效 data 对象")
        return data


def extract_probability(result: dict[str, Any] | None, media_type: str) -> float | None:
    """兼容三类检测服务的字段命名，并忽略明确标记为不可用的结果。"""
    if not result:
        return None
    if result.get("available") is False or result.get("riskLevel") == "unknown":
        return None
    if media_type == "text":
        keys = ("riskProbability", "risk_probability", "fake_probability")
    elif media_type == "audio":
        keys = ("spoof_prob", "fake_probability")
    else:
        keys = ("aggregate_fake_probability", "fake_probability", "average_fake_probability")
    for key in keys:
        value = result.get(key)
        if isinstance(value, (int, float)):
            return max(0.0, min(1.0, float(value)))
    return None
