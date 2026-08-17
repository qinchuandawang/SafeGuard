import tempfile
import unittest
from pathlib import Path

from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.config import Settings
from app.graph import DetectionWorkflow


class FakeDetectionClient:
    def __init__(self, text_probability=0.9, audio_probability=0.9,
                 video_probability=0.9, fallback_probability=None):
        self.text_probability = text_probability
        self.audio_probability = audio_probability
        self.video_probability = video_probability
        self.fallback_probability = fallback_probability
        self.calls = []

    def detect_text(self, text, task_id, trace_id):
        self.calls.append(("text", "deepseek"))
        return {"riskProbability": self.text_probability, "riskLevel": "high"}

    def detect_audio(self, path, model_id, task_id, trace_id):
        self.calls.append(("audio", model_id))
        probability = self.fallback_probability if model_id == "audio-fallback" else self.audio_probability
        return {"spoof_prob": probability, "model_id": model_id or "audio-primary"}

    def detect_video(self, path, model_id, task_id, trace_id):
        self.calls.append(("video", model_id))
        return {"aggregate_fake_probability": self.video_probability, "model_id": model_id or "video-primary"}


class DetectionWorkflowTest(unittest.TestCase):
    def create_workflow(self, client, **overrides):
        with tempfile.TemporaryDirectory() as directory:
            values = {
                "storage_dir": Path(directory),
                "audio_fallback_model": "",
                "video_fallback_model": "",
            }
            values.update(overrides)
            config = Settings(**values)
            return DetectionWorkflow(config, client, MemorySaver()).graph

    @staticmethod
    def state(task_id, media_paths, text=""):
        return {
            "task_id": task_id,
            "trace_id": task_id,
            "media_paths": media_paths,
            "text": text,
            "requested_models": {},
            "errors": [],
            "fallback_attempted": False,
        }

    def test_single_audio_completes_without_human_review(self):
        graph = self.create_workflow(FakeDetectionClient(audio_probability=0.91))
        result = graph.invoke(
            self.state("audio-task", {"audio": "sample.wav"}),
            {"configurable": {"thread_id": "audio-task"}},
        )
        self.assertEqual("completed", result["workflow_status"])
        self.assertEqual("fake", result["decision"])
        self.assertNotIn("__interrupt__", result)

    def test_low_confidence_uses_configured_fallback(self):
        client = FakeDetectionClient(audio_probability=0.52, fallback_probability=0.88)
        graph = self.create_workflow(client, audio_fallback_model="audio-fallback")
        result = graph.invoke(
            self.state("fallback-task", {"audio": "sample.wav"}),
            {"configurable": {"thread_id": "fallback-task"}},
        )
        self.assertTrue(result["fallback_attempted"])
        self.assertEqual("audio-fallback", result["audio_result"]["model_id"])
        self.assertEqual("fake", result["decision"])

    def test_text_audio_and_video_are_fused_in_one_workflow(self):
        client = FakeDetectionClient(
            text_probability=0.8, audio_probability=0.7, video_probability=0.9
        )
        graph = self.create_workflow(client)
        result = graph.invoke(
            self.state(
                "three-modal-task",
                {"audio": "sample.wav", "video": "sample.mp4"},
                "对方要求转账到安全账户",
            ),
            {"configurable": {"thread_id": "three-modal-task"}},
        )
        self.assertEqual("completed", result["workflow_status"])
        self.assertEqual("fake", result["decision"])
        self.assertAlmostEqual(0.805, result["fused_probability"], places=3)
        self.assertEqual({"text", "audio", "video"}, {call[0] for call in client.calls})

    def test_multimodal_conflict_can_resume_after_review(self):
        graph = self.create_workflow(FakeDetectionClient(audio_probability=0.95, video_probability=0.05))
        config = {"configurable": {"thread_id": "review-task"}}
        interrupted = graph.invoke(
            self.state("review-task", {"audio": "sample.wav", "video": "sample.mp4"}), config
        )
        self.assertIn("__interrupt__", interrupted)
        completed = graph.invoke(
            Command(resume={"decision": "fake", "reviewer": "tester"}), config
        )
        self.assertEqual("completed", completed["workflow_status"])
        self.assertEqual("fake", completed["decision"])
        self.assertFalse(completed["review_required"])



if __name__ == "__main__":
    unittest.main()
