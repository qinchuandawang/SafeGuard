import asyncio
import hmac
import re
import shutil
import sqlite3
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Response, UploadFile
from fastapi.concurrency import run_in_threadpool
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import Command
from pydantic import BaseModel

from .clients import DetectionClient
from .config import settings
from .graph import DetectionWorkflow


AUDIO_SUFFIXES = {".wav", ".flac", ".mp3", ".m4a", ".ogg"}
VIDEO_SUFFIXES = {".mp4", ".mov", ".avi", ".mkv", ".webm"}
settings.prepare()
checkpoint_connection = sqlite3.connect(
    settings.storage_dir / "checkpoints.sqlite3", check_same_thread=False
)
checkpointer = SqliteSaver(checkpoint_connection)
workflow = DetectionWorkflow(settings, DetectionClient(settings), checkpointer)
workflow_slots = asyncio.Semaphore(settings.max_concurrent_workflows)
TASK_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    checkpoint_connection.close()


app = FastAPI(title="SafeGuard LangGraph Orchestrator", version="1.0.0", lifespan=lifespan)


class ReviewRequest(BaseModel):
    decision: str
    reviewer: str = "admin"
    comment: str = ""


def verify_internal_token(
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    if not settings.internal_token:
        return
    expected = f"Bearer {settings.internal_token}"
    if not authorization or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="内部服务凭证无效")


async def save_upload(upload: UploadFile, task_id: str, media_type: str) -> str:
    suffix = Path(upload.filename or "").suffix.lower()
    allowed = AUDIO_SUFFIXES if media_type == "audio" else VIDEO_SUFFIXES
    if suffix not in allowed:
        raise HTTPException(status_code=400, detail=f"不支持的{media_type}文件格式: {suffix}")
    task_dir = settings.storage_dir / "uploads" / task_id
    task_dir.mkdir(parents=True, exist_ok=True)
    target = task_dir / f"{media_type}{suffix}"
    size = 0
    with target.open("wb") as output:
        while chunk := await upload.read(1024 * 1024):
            size += len(chunk)
            if size > settings.max_upload_mb * 1024 * 1024:
                output.close()
                target.unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="上传文件超过编排服务限制")
            output.write(chunk)
    return str(target)


def graph_config(task_id: str) -> dict:
    return {"configurable": {"thread_id": task_id}}


def normalize_task_id(task_id: str | None) -> str:
    actual_task_id = task_id or str(uuid.uuid4())
    if not TASK_ID_PATTERN.fullmatch(actual_task_id):
        raise HTTPException(status_code=400, detail="task_id 格式无效")
    return actual_task_id


def reserve_upload_directory(task_id: str) -> None:
    task_dir = settings.storage_dir / "uploads" / task_id
    try:
        task_dir.mkdir(parents=True, exist_ok=False)
    except FileExistsError as exc:
        raise HTTPException(status_code=409, detail="相同 task_id 的工作流正在执行") from exc


def load_existing(task_id: str) -> tuple[dict, bool]:
    snapshot = workflow.graph.get_state(graph_config(task_id))
    values = dict(snapshot.values or {})
    waiting_review = "human_review" in snapshot.next
    return values, waiting_review


def reusable_response(task_id: str) -> tuple[dict, bool] | None:
    values, waiting_review = load_existing(task_id)
    if waiting_review or values.get("workflow_status") == "completed":
        return values, waiting_review
    return None


def has_interrupt(result: dict) -> bool:
    return bool(result.get("__interrupt__"))


def response_data(result: dict, media_type: str | None = None, waiting_review: bool = False) -> dict:
    if media_type:
        data = dict(result.get(f"{media_type}_result") or {})
    else:
        data = {
            "text_result": result.get("text_result"),
            "audio_result": result.get("audio_result"),
            "video_result": result.get("video_result"),
        }
    data["workflow"] = {
        "task_id": result.get("task_id"),
        "status": "waiting_review" if waiting_review or has_interrupt(result) else result.get("workflow_status"),
        "decision": result.get("decision"),
        "fused_probability": result.get("fused_probability"),
        "confidence": result.get("confidence"),
        "fallback_attempted": bool(result.get("fallback_attempted")),
        "review_required": bool(result.get("review_required") or waiting_review or has_interrupt(result)),
        "errors": result.get("errors", []),
        "review": result.get("review"),
    }
    return data


async def invoke(initial_state: dict) -> dict:
    async with workflow_slots:
        return await run_in_threadpool(
            workflow.graph.invoke, initial_state, graph_config(initial_state["task_id"])
        )


@app.get("/health")
def health() -> dict:
    return {"status": "healthy", "component": "langgraph-orchestrator"}


@app.post("/v1/workflows/audio", dependencies=[Depends(verify_internal_token)])
async def audio_workflow(
    file: Annotated[UploadFile, File()],
    task_id: Annotated[str | None, Form()] = None,
    model_id: Annotated[str, Form()] = "",
    x_trace_id: Annotated[str | None, Header()] = None,
) -> dict:
    actual_task_id = normalize_task_id(task_id)
    existing = await run_in_threadpool(reusable_response, actual_task_id)
    if existing:
        return {"code": 0, "message": "reused", "data": response_data(existing[0], "audio", existing[1])}
    reserve_upload_directory(actual_task_id)
    try:
        path = await save_upload(file, actual_task_id, "audio")
    except Exception:
        shutil.rmtree(settings.storage_dir / "uploads" / actual_task_id, ignore_errors=True)
        raise
    try:
        result = await invoke({
            "task_id": actual_task_id, "trace_id": x_trace_id or actual_task_id,
            "media_paths": {"audio": path}, "requested_models": {"audio": model_id},
            "text": "",
            "errors": [], "fallback_attempted": False,
        })
    finally:
        shutil.rmtree(Path(path).parent, ignore_errors=True)
    if result.get("workflow_status") == "failed":
        raise HTTPException(status_code=502, detail=result.get("errors") or "音频工作流执行失败")
    return {"code": 0, "message": "success", "data": response_data(result, "audio")}


@app.post("/v1/workflows/video", dependencies=[Depends(verify_internal_token)])
async def video_workflow(
    file: Annotated[UploadFile, File()],
    task_id: Annotated[str | None, Form()] = None,
    model_id: Annotated[str, Form()] = "",
    x_trace_id: Annotated[str | None, Header()] = None,
) -> dict:
    actual_task_id = normalize_task_id(task_id)
    existing = await run_in_threadpool(reusable_response, actual_task_id)
    if existing:
        return {"code": 0, "message": "reused", "data": response_data(existing[0], "video", existing[1])}
    reserve_upload_directory(actual_task_id)
    try:
        path = await save_upload(file, actual_task_id, "video")
    except Exception:
        shutil.rmtree(settings.storage_dir / "uploads" / actual_task_id, ignore_errors=True)
        raise
    try:
        result = await invoke({
            "task_id": actual_task_id, "trace_id": x_trace_id or actual_task_id,
            "media_paths": {"video": path}, "requested_models": {"video": model_id},
            "text": "",
            "errors": [], "fallback_attempted": False,
        })
    finally:
        shutil.rmtree(Path(path).parent, ignore_errors=True)
    if result.get("workflow_status") == "failed":
        raise HTTPException(status_code=502, detail=result.get("errors") or "视频工作流执行失败")
    return {"code": 0, "message": "success", "data": response_data(result, "video")}


@app.post("/v1/workflows/multimodal", dependencies=[Depends(verify_internal_token)])
async def multimodal_workflow(
    response: Response,
    audio: Annotated[UploadFile, File()],
    video: Annotated[UploadFile, File()],
    task_id: Annotated[str | None, Form()] = None,
    audio_model_id: Annotated[str, Form()] = "",
    video_model_id: Annotated[str, Form()] = "",
    text: Annotated[str, Form()] = "",
    x_trace_id: Annotated[str | None, Header()] = None,
) -> dict:
    actual_task_id = normalize_task_id(task_id)
    existing = await run_in_threadpool(reusable_response, actual_task_id)
    if existing:
        if existing[1]:
            response.status_code = 202
        return {"code": 0, "message": "reused", "data": response_data(existing[0], waiting_review=existing[1])}
    reserve_upload_directory(actual_task_id)
    try:
        audio_path = await save_upload(audio, actual_task_id, "audio")
        video_path = await save_upload(video, actual_task_id, "video")
    except Exception:
        shutil.rmtree(settings.storage_dir / "uploads" / actual_task_id, ignore_errors=True)
        raise
    try:
        result = await invoke({
            "task_id": actual_task_id, "trace_id": x_trace_id or actual_task_id,
            "media_paths": {"audio": audio_path, "video": video_path},
            "text": text.strip(),
            "requested_models": {"audio": audio_model_id, "video": video_model_id},
            "errors": [], "fallback_attempted": False,
        })
    except Exception:
        shutil.rmtree(Path(audio_path).parent, ignore_errors=True)
        raise
    if not has_interrupt(result):
        shutil.rmtree(Path(audio_path).parent, ignore_errors=True)
    else:
        response.status_code = 202
    return {
        "code": 0, "message": "waiting_review" if has_interrupt(result) else "success",
        "data": response_data(result)
    }


@app.post("/v1/workflows/{task_id}/resume", dependencies=[Depends(verify_internal_token)])
async def resume_workflow(task_id: str, review: ReviewRequest) -> dict:
    normalize_task_id(task_id)
    if review.decision not in {"fake", "real", "uncertain"}:
        raise HTTPException(status_code=400, detail="decision 只能是 fake、real 或 uncertain")
    existing, waiting_review = await run_in_threadpool(load_existing, task_id)
    if existing.get("workflow_status") == "completed" and existing.get("review"):
        return {"code": 0, "message": "reused", "data": response_data(existing)}
    if not waiting_review:
        raise HTTPException(status_code=409, detail="工作流当前不处于人工审核节点")
    async with workflow_slots:
        result = await run_in_threadpool(
            workflow.graph.invoke, Command(resume=review.model_dump()), graph_config(task_id)
        )
    shutil.rmtree(settings.storage_dir / "uploads" / task_id, ignore_errors=True)
    return {"code": 0, "message": "success", "data": response_data(result)}
