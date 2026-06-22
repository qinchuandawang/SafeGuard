# 音频伪造检测服务

基于 Wav2Vec2 微调模型的 Flask 推理服务，默认端口 `5000`。

## 启动

推荐从 `ai-services` 统一启动：

```bash
cd ai-services
python run.py
```

单独启动音频服务：

```bash
cd ai-services/audio
python app.py
```

## 模型目录

服务默认读取：

```text
ai-services/audio/pretrained/asvspoof-finetuned/
```

该目录需要包含 `config.json`、`preprocessor_config.json`、`tokenizer_config.json`、`vocab.json` 和 `model.safetensors`。其中大权重文件 `model.safetensors` 不提交到 Git，重新克隆仓库后需要从备份或组员提供的模型包恢复。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查，返回模型目录和加载状态 |
| POST | `/audio/detect` | 上传音频文件并返回伪造概率 |
