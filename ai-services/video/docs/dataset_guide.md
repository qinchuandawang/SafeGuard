# WildDeepfake 数据集放置指南

## 📍 数据集放置位置

将下载的 WildDeepfake 数据集放置到项目的 `data/` 目录下。

### 完整路径

```
c:\Users\86198\Desktop\XceptionNet\data\
```

## 📁 目录结构

### 标准结构

```
XceptionNet/
└── data/
    ├── train/
    │   ├── real/              # 训练集 - 真实人脸帧
    │   │   ├── 0/            # 人脸序列 0
    │   │   │   ├── 0.jpg     # 第 0 帧
    │   │   │   ├── 1.jpg     # 第 1 帧
    │   │   │   └── ...       # 更多帧
    │   │   ├── 1/            # 人脸序列 1
    │   │   └── ...           # 更多序列
    │   └── fake/              # 训练集 - 伪造人脸帧
    │       ├── 0/            # 人脸序列 0
    │       ├── 1/            # 人脸序列 1
    │       └── ...
    ├── valid/
    │   ├── real/              # 验证集 - 真实人脸帧
    │   └── fake/              # 验证集 - 伪造人脸帧
    └── test/
        ├── real/              # 测试集 - 真实人脸帧
        └── fake/              # 测试集 - 伪造人脸帧
```



### 步骤 2：解压文件

下载的格式通常是 `tar.gz`，需要解压：

```bash
# 方法 1：使用 Python 脚本
python scripts/download_dataset.py --tar_path 下载的文件.tar.gz --output_dir data

# 方法 2：使用 7-Zip（Windows）
# 右键点击文件 → 7-Zip → 解压到 "wild-deepfake/"

# 方法 3：使用命令行（Linux/Mac）
tar -xzf 文件.tar.gz -C data/
```

### 步骤 3：整理目录结构

解压后，你可能需要手动整理目录结构。

**假设解压后的原始结构**：
```
data/raw/
├── real_train/
│   ├── 0/
│   ├── 1/
│   └── ...
├── fake_train/
│   ├── 0/
│   └── ...
├── real_test/
└── fake_test/
```

**需要整理成**：
```bash
# 在 data/ 目录下创建文件夹
mkdir -p data/train/real
mkdir -p data/train/fake
mkdir -p data/valid/real
mkdir -p data/valid/fake
mkdir -p data/test/real
mkdir -p data/test/fake

# 移动文件（Windows PowerShell）
Move-Item data/raw/real_train/* data/train/real/
Move-Item data/raw/fake_train/* data/train/fake/
Move-Item data/raw/real_test/* data/test/real/
Move-Item data/raw/fake_test/* data/test/fake/

# 如果有验证集
# Move-Item data/raw/real_valid/* data/valid/real/
# Move-Item data/raw/fake_valid/* data/valid/fake/
```

### 步骤 4：验证数据集

运行以下命令验证数据集是否正确放置：

```bash
python -c "from utils.data_loader import DeepFakeDataset; dataset = DeepFakeDataset('data', 'train')"
```

如果看到类似输出，说明数据集加载成功：
```
加载了 5000 张图片 (train)
  真实图片：2500
  伪造图片：2500
```

## 🔍 常见问题

### Q1: 数据集应该放在绝对路径还是相对路径？

**A**: 使用相对路径即可。代码中默认使用 `data/` 作为数据目录。

如果使用绝对路径，需要在训练时指定：
```bash
python train.py --data_dir D:\datasets\WildDeepfake
```

### Q2: 验证集应该叫 `val` 还是 `valid`？

**A**: WildDeepfake 官方使用 `valid`，但代码已经做了兼容处理，两个名称都支持。

### Q3: 图片格式必须是 JPG 吗？

**A**: 是的，代码中默认查找 `.jpg` 文件。如果是 PNG 格式，需要修改 `data_loader.py`：

```python
# 修改这行
for image_file in sequence_dir.glob("*.jpg"):

# 改为
for image_file in sequence_dir.glob("*.png"):
# 或者同时支持两种格式
for image_file in sequence_dir.glob("*.jpg"):
    self.image_paths.append(str(image_file))
for image_file in sequence_dir.glob("*.png"):
    self.image_paths.append(str(image_file))
```



### Q5: 如何确认数据集是否正确？

**A**: 检查以下几点：

1. **目录层级正确**：
   ```
   data/train/real/0/0.jpg  ✓ 正确
   data/train/real/0.jpg    ✗ 错误（缺少序列文件夹）
   ```

2. **有图片文件**：
   每个序列文件夹下应该有若干 `.jpg` 文件

3. **标签平衡**：
   运行数据加载器查看真实和伪造图片数量

## 📊 数据集统计

WildDeepfake 数据集包含：

- **总人脸序列**：7,314 个
- **总视频数**：707 个
- **来源**：互联网真实 DeepFake 视频
- **特点**：
  - 更具挑战性
  - 接近真实场景
  - 多样性好

## 🚀 下一步

数据集准备好后，就可以开始训练：

```bash
# 开始训练
python train.py --data_dir data --batch_size 32 --num_epochs 50

# 使用 GPU 训练（如果有）
# 会自动检测并使用 CUDA
```

## 📞 需要帮助？

遇到问题可以：
1. 查看 `scripts/download_dataset.py` 的详细指南
2. 检查项目 README.md
3. 联系项目团队

---

**最后更新**：2026 年 3 月 27 日
