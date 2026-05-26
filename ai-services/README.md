# SafeGuard AI 检测服务

统一管理音频伪造检测 (Wav2Vec2) 和视频换脸检测 (XceptionNet)。

## 目录结构

```
ai-services/
├── audio/              # 音频检测模块 (port 5001)
│   ├── src/
│   │   ├── app.py          # Flask 服务入口
│   │   ├── utils.py        # 音频处理工具
│   │   └── train_wav2vec2.py
│   ├── outputs/            # 训练产出的模型
│   ├── pretrained/         # 预训练模型
│   └── requirements.txt
├── video/              # 视频检测模块 (port 5002)
│   ├── api/app.py          # Flask 服务入口
│   ├── models/xception.py  # XceptionNet 模型
│   ├── utils/              # 视频处理工具
│   ├── train.py            # 训练脚本
│   └── requirements.txt
├── run.py              # 🔥 统一启动入口
├── requirements.txt    # 合并依赖
└── README.md
```

## 快速启动

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 一键启动两个服务
python run.py

# 音频检测: http://localhost:5001
# 视频检测: http://localhost:5002
```

## 单独启动

```bash
# 音频
cd audio && python src/app.py          # 默认 5001

# 视频
cd video && python api/app.py           # 默认 5002
```

## API 接口

### 音频检测 (port 5001)
- `GET  /health`         健康检查
- `POST /audio/detect`   音频伪造检测

### 视频检测 (port 5002)
- `GET  /api/health`            健康检查
- `POST /api/detect/image`      图片换脸检测
- `POST /api/detect/video`      视频换脸检测
