"""
视频时序分析模块
提供帧间一致性检测、滑动窗口平滑、视频级评分
用于提升 DeepFake 视频检测的时序鲁棒性
"""

import numpy as np
from typing import Optional
from scipy.ndimage import uniform_filter1d


class TemporalSmoother:
    """滑动窗口平滑器"""

    def __init__(self, window_size: int = 5):
        self.window_size = max(3, window_size)

    def smooth(self, probs: np.ndarray) -> np.ndarray:
        if len(probs) < self.window_size:
            return probs
        return uniform_filter1d(probs.astype(np.float64), size=self.window_size)


class ConsistencyDetector:
    """帧间一致性检测器"""

    def __init__(self, threshold: float = 0.3):
        self.threshold = threshold

    def detect(self, probs: np.ndarray) -> dict:
        if len(probs) < 2:
            return {"inconsistent_frames": [], "max_jump": 0.0, "jump_count": 0}
        diffs = np.abs(np.diff(probs))
        jump_indices = np.where(diffs > self.threshold)[0]
        return {
            "inconsistent_frames": [int(i) for i in jump_indices],
            "max_jump": float(diffs.max()) if len(diffs) > 0 else 0.0,
            "jump_count": int(len(jump_indices)),
        }

class VideoScorer:
    """视频级评分器 - 综合帧级和时序特征"""

    def __init__(self, smooth_window: int = 5, jump_threshold: float = 0.3):
        self.smoother = TemporalSmoother(window_size=smooth_window)
        self.detector = ConsistencyDetector(threshold=jump_threshold)

    def score(self, frame_probs: np.ndarray) -> dict:
        if len(frame_probs) == 0:
            return {"score": 0.0, "is_fake": False, "confidence": "low"}

        probs = np.array(frame_probs, dtype=np.float64)
        smoothed = self.smoother.smooth(probs)
        consistency = self.detector.detect(smoothed)

        mean_prob = float(np.mean(smoothed))
        max_prob = float(np.max(smoothed))
        std_prob = float(np.std(smoothed))
        trend = float(np.polyfit(np.arange(len(smoothed)), smoothed, 1)[0]) if len(smoothed) >= 2 else 0.0

        n_frames = len(smoothed)
        high_prob_ratio = float(np.mean(smoothed > 0.5))

        base_score = mean_prob

        std_bonus = min(std_prob * 0.5, 0.15)
        jump_penalty = min(consistency["jump_count"] / max(n_frames, 1) * 0.5, 0.15)
        trend_bonus = abs(trend) * min(n_frames, 30) * 0.3

        combined_score = base_score + std_bonus - jump_penalty + trend_bonus
        combined_score = float(np.clip(combined_score, 0.0, 1.0))

        if combined_score > 0.8:
            confidence = "high"
        elif combined_score > 0.5:
            confidence = "medium"
        else:
            confidence = "low"

        return {
            "score": round(combined_score, 4),
            "is_fake": combined_score > 0.5,
            "confidence": confidence,
            "frame_level": {
                "mean_prob": round(mean_prob, 4),
                "max_prob": round(max_prob, 4),
                "std_prob": round(std_prob, 4),
            },
            "temporal_features": {
                "trend": round(trend, 6),
                "high_prob_frame_ratio": round(high_prob_ratio, 4),
                "n_frames": n_frames,
                "inconsistent_frame_count": consistency["jump_count"],
                "max_frame_jump": round(consistency["max_jump"], 4),
            },
            "frame_probs_smoothed": [round(float(p), 4) for p in smoothed],
        }
