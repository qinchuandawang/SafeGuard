# SafeGuard AI 检测服务

统一管理音频伪造检测 (Wav2Vec2) 和视频换脸检测 (XceptionNet)。

## 环境要求

- **Python 3.11**（安装时务必勾选 **Add Python to PATH**）
- 虚拟环境统一放在 `ai-services/.venv`，不放仓库根目录

## 目录结构

```
ai-services/
├── audio/              # 音频检测模块 (port 5000)
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
├── run.py              # 统一启动入口
├── requirements.txt    # 合并依赖
└── README.md
```

## 快速启动

### 1. 创建虚拟环境

在 `ai-services` 目录下执行（不要在仓库根目录执行）：

```bash
cd ai-services
python -m venv .venv
```

> 不使用 `py -3.11`。如果系统找不到 `python` 命令，请安装 Python 3.11 并勾选 **Add to PATH**，或在 PyCharm 中直接选择已有解释器。

### 2. 升级 pip 并安装依赖

```bash
.\.venv\Scripts\python.exe -m pip install -U pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

### 3. 启动服务

```bash
cd ai-services
python run.py
```

或使用虚拟环境直接启动：

```bash
.\.venv\Scripts\python.exe run.py
```

启动后：
- 音频检测：http://localhost:5000
- 视频检测：http://localhost:5002

## 激活虚拟环境（可选）

如需手动激活后进行交互式开发：

**PowerShell:**
```powershell
.\.venv\Scripts\Activate.ps1
```

**CMD:**
```cmd
.\.venv\Scripts\activate.bat
```

激活后终端前缀会显示 `(.venv)`，此时可直接使用 `python`、`pip` 等命令。

## 单独启动

```bash
# 音频
cd audio && python run.py               # 默认 5000

# 视频
cd video && python api/app.py           # 默认 5002
```

## PyCharm 配置

在 PyCharm 中选择解释器路径：

```
D:\Git\SafeGuard\ai-services\.venv\Scripts\python.exe
```

操作：File → Settings → Project → Python Interpreter → 齿轮图标 → Add → Existing environment → 选择以上路径。

## API 接口

### 音频检测 (port 5000)
- `GET  /health`         健康检查
- `POST /audio/detect`   音频伪造检测

### 视频检测 (port 5002)
- `GET  /api/health`            健康检查
- `POST /api/detect/image`      图片换脸检测
- `POST /api/detect/video`      视频换脸检测