# 视频换脸检测服务

基于 XceptionNet 的图片/视频 DeepFake 检测 Flask 服务，默认端口 `5002`。

## 目录结构

```text
ai-services/video/
├── api/app.py              # Flask 服务入口
├── models/xception.py      # XceptionNet 模型定义
├── pretrained/             # 本地模型目录（大权重文件不提交 Git）
├── utils/                  # 视频抽帧、人脸检测、数据加载工具
├── train.py                # 训练脚本
└── docs/api.md             # 接口细节
```

## 启动

推荐从 `ai-services` 统一启动：

```bash
cd ai-services
python run.py
```

单独启动视频服务：

```bash
cd ai-services/video
python api/app.py
```

## 模型文件

服务默认读取：

```text
ai-services/video/pretrained/best_model.pth
```

该权重文件不提交到 Git。重新克隆仓库后，需要从备份或组员提供的模型文件恢复到上述路径。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| POST | `/api/detect/image` | 上传图片并返回伪造概率、人脸位置等信息 |
| POST | `/api/detect/video` | 上传视频并返回平均伪造概率、最大伪造概率和帧级分析 |

示例：

```bash
curl -X POST -F "file=@test_image.jpg" http://localhost:5002/api/detect/image
curl -X POST -F "file=@test_video.mp4" http://localhost:5002/api/detect/video
```

## 训练

如需重新训练：

```bash
cd ai-services/video
python train.py --data_dir data --batch_size 32 --num_epochs 50
```

数据集目录约定见 [docs/dataset_guide.md](docs/dataset_guide.md)，接口返回格式见 [docs/api.md](docs/api.md)。
