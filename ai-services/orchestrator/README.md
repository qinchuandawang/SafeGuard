# SafeGuard LangGraph 编排服务

该服务只管理 AI 推理节点状态，不管理 Java 业务任务状态。Java 后端仍是任务状态和最终结果的事实来源。

## 工作流

```text
START -> 文本检测（Spring AI + DeepSeek）--\
START -> 音频检测（Wav2Vec2）-------------> 结果融合 -> 冲突处理 -> 最终结果
START -> 视频检测（Xception）-------------/             |
                                           高置信度冲突 -> 人工审核
```

文本、音频和视频节点在包含对应输入时并行执行；文本节点通过 Java 统一 LLM Gateway 调用 Spring AI + DeepSeek，音视频节点调用专用模型服务。网关统一维护 Token 预算、缓存、重试和 Prompt 版本，LangGraph 只负责节点级超时与状态编排。汇聚节点按可用模态动态归一化权重，发生高置信度冲突时暂停等待人工审核。备用模型目前仅保留路由扩展点，未配置权重时不会伪装成已执行。

Java 业务主链路通过 `POST /api/detection/multi` 调用多模态工作流；该接口不接受客户端提交的伪造模型结果。小程序因为原生 `wx.uploadFile` 单次只能上传一个文件，先调用 Java 的 `/api/detection/multi/audio-stage` 获取一次性 `audioToken`，再携带 Token 上传视频创建任务。

## 启动

```powershell
python -m pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 5003
```

接口：

- `POST /v1/workflows/audio`
- `POST /v1/workflows/video`
- `POST /v1/workflows/multimodal`
- `POST /v1/workflows/{task_id}/resume`
- `GET /health`

生产环境应设置 `ORCHESTRATOR_INTERNAL_TOKEN`，调用方使用 `Authorization: Bearer <token>`。不要将令牌提交到仓库。
