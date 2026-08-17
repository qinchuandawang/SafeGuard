import operator
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from .clients import DetectionClient, extract_probability
from .config import Settings


class WorkflowState(TypedDict, total=False):
    task_id: str
    trace_id: str
    media_paths: dict[str, str]
    text: str
    requested_models: dict[str, str]
    text_result: dict[str, Any]
    audio_result: dict[str, Any]
    video_result: dict[str, Any]
    errors: Annotated[list[str], operator.add]
    fallback_attempted: bool
    fused_probability: float
    confidence: float
    decision: str
    route: str
    review_required: bool
    review: dict[str, Any]
    workflow_status: str


class DetectionWorkflow:
    """多模型检测图，业务任务状态仍由 Java 后端维护。"""

    def __init__(self, config: Settings, client: DetectionClient, checkpointer: Any):
        self.config = config
        self.client = client
        self.graph = self._build().compile(checkpointer=checkpointer)

    def _build(self) -> StateGraph:
        builder = StateGraph(WorkflowState)
        builder.add_node("text_primary", self._text_primary)
        builder.add_node("audio_primary", self._audio_primary)
        builder.add_node("video_primary", self._video_primary)
        builder.add_node("fuse", self._fuse)
        builder.add_node("fallback", self._fallback)
        builder.add_node("refuse", self._fuse)
        builder.add_node("human_review", self._human_review)
        builder.add_node("finalize", self._finalize)
        builder.add_edge(START, "text_primary")
        builder.add_edge(START, "audio_primary")
        builder.add_edge(START, "video_primary")
        builder.add_edge(["text_primary", "audio_primary", "video_primary"], "fuse")
        builder.add_conditional_edges("fuse", self._route, {
            "fallback": "fallback", "review": "human_review", "finalize": "finalize"
        })
        builder.add_edge("fallback", "refuse")
        builder.add_conditional_edges("refuse", self._route, {
            "fallback": "finalize", "review": "human_review", "finalize": "finalize"
        })
        builder.add_edge("human_review", "finalize")
        builder.add_edge("finalize", END)
        return builder

    def _text_primary(self, state: WorkflowState) -> dict[str, Any]:
        text = state.get("text", "").strip()
        if not text:
            return {}
        try:
            result = self.client.detect_text(text, state["task_id"], state["trace_id"])
            if result.get("riskLevel") == "unknown":
                result["available"] = False
            return {"text_result": result}
        except Exception as exc:
            return {"errors": [f"text_primary: {exc}"]}

    def _audio_primary(self, state: WorkflowState) -> dict[str, Any]:
        path = state.get("media_paths", {}).get("audio")
        if not path:
            return {}
        try:
            result = self.client.detect_audio(
                path, state.get("requested_models", {}).get("audio", ""),
                state["task_id"], state["trace_id"]
            )
            return {"audio_result": result}
        except Exception as exc:
            return {"errors": [f"audio_primary: {exc}"]}

    def _video_primary(self, state: WorkflowState) -> dict[str, Any]:
        path = state.get("media_paths", {}).get("video")
        if not path:
            return {}
        try:
            result = self.client.detect_video(
                path, state.get("requested_models", {}).get("video", ""),
                state["task_id"], state["trace_id"]
            )
            return {"video_result": result}
        except Exception as exc:
            return {"errors": [f"video_primary: {exc}"]}

    def _fuse(self, state: WorkflowState) -> dict[str, Any]:
        text_probability = extract_probability(state.get("text_result"), "text")
        audio_probability = extract_probability(state.get("audio_result"), "audio")
        video_probability = extract_probability(state.get("video_result"), "video")
        weighted_probabilities = [
            (text_probability, self.config.text_weight),
            (audio_probability, self.config.audio_weight),
            (video_probability, self.config.video_weight),
        ]
        available = [(value, weight) for value, weight in weighted_probabilities if value is not None]
        if not available:
            return {
                "fused_probability": 0.5, "confidence": 0.0, "decision": "failed",
                "route": "finalize", "workflow_status": "failed"
            }

        weight_sum = sum(weight for _, weight in available)
        fused = sum(value * weight for value, weight in available) / weight_sum
        confidence = abs(fused - 0.5) * 2
        confident_labels = {
            value >= 0.5
            for value, _ in available
            if abs(value - 0.5) * 2 >= self.config.conflict_confidence_threshold
        }
        conflict = len(confident_labels) > 1
        has_fallback = bool(
            (audio_probability is not None and self.config.audio_fallback_model)
            or (video_probability is not None and self.config.video_fallback_model)
        )
        if conflict:
            route = "review"
        elif (
            confidence < self.config.fallback_confidence_threshold
            and not state.get("fallback_attempted")
            and has_fallback
        ):
            route = "fallback"
        else:
            route = "finalize"

        decision = "fake" if fused >= 0.55 else "real" if fused <= 0.45 else "uncertain"
        return {
            "fused_probability": fused,
            "confidence": confidence,
            "decision": decision,
            "route": route,
            "review_required": route == "review",
        }

    def _fallback(self, state: WorkflowState) -> dict[str, Any]:
        updates: dict[str, Any] = {"fallback_attempted": True}
        errors: list[str] = []
        audio_path = state.get("media_paths", {}).get("audio")
        video_path = state.get("media_paths", {}).get("video")
        try:
            if audio_path and self.config.audio_fallback_model:
                updates["audio_result"] = self.client.detect_audio(
                    audio_path, self.config.audio_fallback_model, state["task_id"], state["trace_id"]
                )
        except Exception as exc:
            errors.append(f"audio_fallback: {exc}")
        try:
            if video_path and self.config.video_fallback_model:
                updates["video_result"] = self.client.detect_video(
                    video_path, self.config.video_fallback_model, state["task_id"], state["trace_id"]
                )
        except Exception as exc:
            errors.append(f"video_fallback: {exc}")
        if errors:
            updates["errors"] = errors
        return updates

    def _human_review(self, state: WorkflowState) -> dict[str, Any]:
        review = interrupt({
            "task_id": state["task_id"],
            "reason": "文本、音频或视频检测给出了高置信度冲突结论",
            "text_probability": extract_probability(state.get("text_result"), "text"),
            "audio_probability": extract_probability(state.get("audio_result"), "audio"),
            "video_probability": extract_probability(state.get("video_result"), "video"),
        })
        approved_decision = str((review or {}).get("decision", "uncertain"))
        if approved_decision not in {"fake", "real", "uncertain"}:
            approved_decision = "uncertain"
        return {
            "review": review or {}, "decision": approved_decision,
            "review_required": False, "route": "finalize"
        }

    def _finalize(self, state: WorkflowState) -> dict[str, Any]:
        status = "failed" if state.get("decision") == "failed" else "completed"
        return {"workflow_status": status}

    @staticmethod
    def _route(state: WorkflowState) -> str:
        return state.get("route", "finalize")
