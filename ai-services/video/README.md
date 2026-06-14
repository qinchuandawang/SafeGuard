# 视频换脸检测模块

"诈骗克星"项目的视频换脸检测模块，基于 XceptionNet 实现 DeepFake 视频检测。

## 项目结构

```
XceptionNet/
├── data/                    # 数据集目录
│   ├── train/              # 训练集（real/fake）
│   ├── valid/              # 验证集（real/fake）
│   └── test/               # 测试集（real/fake）
├── models/                  # 模型定义
│   └── xception.py         # XceptionNet 模型
├── utils/                   # 工具函数
│   ├── video_processor.py  # 视频处理工具
│   └── data_loader.py      # 数据加载器
├── api/                     # Flask 服务
│   └── app.py              # API 接口
├── checkpoints/             # 模型权重
├── logs/                    # 训练日志
├── scripts/                 # 脚本工具
│   └── download_dataset.py # 数据集下载
├── train.py                 # 训练脚本
├── requirements.txt         # 依赖包
└── README.md               # 项目文档
```

## 快速开始

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

### 2. 准备数据集

**使用 WildDeepfake 数据集**

下载 WildDeepfake 数据集并放到 `data/` 目录：

```bash
python scripts/download_dataset.py
```

**数据集目录结构**：

```
data/
├── train/
│   ├── real/              # 真实人脸帧
│   │   ├── 0/            # 人脸序列 0
│   │   ├── 1/            # 人脸序列 1
│   │   └── ...
│   └── fake/              # 伪造人脸帧
│       ├── 0/            # 人脸序列 0
│       └── ...
├── valid/
│   ├── real/
│   └── fake/
└── test/
    ├── real/
    └── fake/
```

**数据集说明**：
- **训练集（train）**：用于模型训练
- **验证集（valid）**：用于调参和早停
- **测试集（test）**：用于最终评估

**注意**：WildDeepfake 数据集已预处理为图片帧，无需额外提取视频帧。

### 3. 训练模型

```bash
python train.py --data_dir data --batch_size 32 --num_epochs 50
```

训练完成后，最佳模型会保存在 `checkpoints/best_model.pth`

### 4. 启动 API 服务

```bash
python api/app.py
```

服务启动后，可以通过以下接口调用：

- 健康检查：`GET http://localhost:5002/api/health`
- 图片检测：`POST http://localhost:5002/api/detect/image`
- 视频检测：`POST http://localhost:5002/api/detect/video`

### 5. 测试 API

**测试图片检测**：
```bash
curl -X POST -F "file=@test_image.jpg" http://localhost:5002/api/detect/image
```

**测试视频检测**：
```bash
curl -X POST -F "file=@test_video.mp4" http://localhost:5002/api/detect/video
```

## API 接口

### 健康检查
- **GET** `/api/health`

### 图片检测
- **POST** `/api/detect/image`
- 返回：伪造概率、人脸位置、帧级分析

### 视频检测
- **POST** `/api/detect/video`
- 返回：平均伪造概率、最大伪造概率、每帧分析结果

### 文本检测（预留）
- **POST** `/api/detect/text`
- 由大模型处理

## 模型架构

XceptionNet 基于深度可分离卷积，具有优秀的特征提取能力：
- 输入尺寸：299x299
- 输出：二分类（真实/伪造）
- 目标准确率：≥80%

## 技术栈

- **深度学习框架**: PyTorch
- **模型**: XceptionNet
- **Web 框架**: Flask
- **图像处理**: OpenCV, PIL, face_recognition
- **数据集**: WildDeepfake

## 性能指标

- 验证集准确率：≥80%
- 单张图片检测时间：<1s
- 单视频检测时间：<30s（30 帧）

## 与大模型集成

检测结果格式化为 JSON，便于大模型理解和解释：

```json
{
  "is_fake": true,
  "fake_probability": 0.95,
  "confidence": "high",
  "evidence": [
    "多帧检测到高伪造概率",
    "人脸区域特征异常"
  ]
}
```

## 下一步

1. 下载 WildDeepfake 数据集
2. 安装依赖包
3. 开始训练模型
4. 测试 API 接口

## 联系方式

如有问题，请联系项目负责人。