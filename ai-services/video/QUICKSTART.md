# 快速开始指南

## 📦 项目结构已搭建完成！

```
XceptionNet/
├── api/                    # Flask API 服务
│   ├── __init__.py
│   └── app.py             # 检测接口
├── models/                 # 模型定义
│   ├── __init__.py
│   └── xception.py        # XceptionNet 模型
├── utils/                  # 工具函数
│   ├── __init__.py
│   ├── data_loader.py     # 数据加载器
│   └── video_processor.py # 视频处理工具
├── scripts/                # 脚本工具
│   └── download_dataset.py # 数据集下载
├── data/                   # 数据集目录（需下载）
├── checkpoints/            # 模型权重（训练后生成）
├── logs/                   # 日志目录
├── train.py                # 训练脚本
├── config.yaml             # 配置文件
├── requirements.txt        # 依赖包
└── README.md              # 项目文档
```

## 🚀 接下来的步骤

### Step 1: 安装依赖（必须）

打开终端，运行：

```bash
pip install -r requirements.txt
```

**注意**：`face_recognition` 和 `dlib` 可能需要额外的编译环境，如果遇到安装问题：

**Windows 用户**：
```bash
# 先安装 Visual C++ 构建工具
# 下载地址：https://visualstudio.microsoft.com/visual-cpp-build-tools/

# 然后安装 dlib
pip install dlib

# 再安装 face_recognition
pip install face_recognition
```

### Step 2: 下载 Celeb-DF-v2 数据集（必须）

1. **访问数据集网站**：
   - 官方网站：https://www.cs.albany.edu/~lsw/celeb-df-v2/
   - 填写申请表获取下载链接

2. **或者使用 Kaggle 镜像**：
   - https://www.kaggle.com/datasets/xhlulu/celeb-df-v2

3. **数据集说明**：
   - 训练集：真实视频 590 个，伪造视频 4078 个
   - 测试集：真实视频 795 个，伪造视频 1191 个
   - 总大小：约 50GB

4. **整理目录结构**：
   ```
   data/
   ├── train/
   │   ├── real/          # 真实视频
   │   └── fake/          # 伪造视频
   ├── val/
   │   ├── real/
   │   └── fake/
   └── test/
       ├── real/
       └── fake/
   ```

### Step 3: 预处理数据集（可选）

如果下载的是原始视频文件，需要提取人脸：

```bash
# 暂时跳过，后续会提供详细指导
```

### Step 4: 训练模型（核心任务）

```bash
# 开始训练
python train.py --data_dir data --batch_size 32 --num_epochs 50

# 如果有 GPU，会自动使用
# 训练时间：约 2-4 小时（取决于 GPU 性能）
```

**训练目标**：验证集准确率 ≥80%

### Step 5: 启动 API 服务

```bash
python api/app.py
```

服务启动后，可以通过以下接口调用：

- 健康检查：`GET http://localhost:5002/api/health`
- 图片检测：`POST http://localhost:5002/api/detect/image`
- 视频检测：`POST http://localhost:5002/api/detect/video`

### Step 6: 测试 API

**测试图片检测**：
```bash
curl -X POST -F "file=@test_image.jpg" http://localhost:5002/api/detect/image
```

**测试视频检测**：
```bash
curl -X POST -F "file=@test_video.mp4" http://localhost:5002/api/detect/video
```

## 📝 你现在需要做什么？

### 优先级排序：

1. **立即执行**：安装依赖包
   ```bash
   pip install -r requirements.txt
   ```

2. **今天完成**：下载 Celeb-DF-v2 数据集
   - 访问官网申请下载
   - 预计下载时间：2-6 小时（取决于网速）

3. **明天开始**：数据预处理和模型训练
   - 我会帮你编写数据预处理脚本
   - 开始训练模型

## 💡 常见问题

**Q: face_recognition 安装失败怎么办？**
A: 先安装 dlib，需要 Visual C++ 构建工具。或者使用替代方案（MTCNN）。

**Q: 没有 GPU 能训练吗？**
A: 可以，但训练时间会很长（10+ 小时）。建议使用 Colab 或实验室 GPU 服务器。

**Q: 数据集下载太慢？**
A: 可以使用百度网盘镜像或联系项目负责人获取数据集副本。

**Q: 训练时内存不足？**
A: 减小 batch_size（如 16 或 8），或减少数据加载线程数。

## 📞 需要帮助？

遇到问题随时问我，我会帮你解决！

下一步建议：**先安装依赖包，然后开始下载数据集**