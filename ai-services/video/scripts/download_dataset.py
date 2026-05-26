"""
WildDeepfake 数据集下载和预处理脚本
"""

import os
import shutil
from pathlib import Path
import argparse
import tarfile


def download_wilddeepfake(output_dir='data'):
    """
    下载 WildDeepfake 数据集
    
    数据集下载地址：
    - GitHub: https://github.com/deepfakeinthewild/deepfake-in-the-wild
    - Hugging Face: https://huggingface.co/datasets/wild-deepfake
    
    注意：需要填写申请表获取下载链接
    """
    
    print("=" * 70)
    print("WildDeepfake 数据集下载指南")
    print("=" * 70)
    
    print("\n【渠道 1】Hugging Face（推荐）")
    print("-" * 70)
    print("  访问：https://huggingface.co/datasets/wild-deepfake")
    print("  优点：下载速度快，支持断点续传")
    print("  步骤：")
    print("    1. 访问 Hugging Face 网站")
    print("    2. 注册/登录账号")
    print("    3. 搜索 'wild-deepfake'")
    print("    4. 点击下载数据集")
    
    print("\n【渠道 2】GitHub 官方")
    print("-" * 70)
    print("  访问：https://github.com/deepfakeinthewild/deepfake-in-the-wild")
    print("  步骤：")
    print("    1. 填写申请表（需要学术邮箱）")
    print("    2. 等待审核（1-3 个工作日）")
    print("    3. 收到下载链接")
    
    print("\n" + "=" * 70)
    print("数据集信息")
    print("=" * 70)
    print("  - 训练集（train）：已提取的人脸帧图片")
    print("  - 验证集（valid）：已提取的人脸帧图片")
    print("  - 测试集（test）：已提取的人脸帧图片")
    print("  - 总图片数：约 7,314 张人脸序列")
    print("  - 来源：互联网真实 DeepFake 视频")
    print("  - 特点：更具挑战性，接近真实场景")
    
    print("\n" + "=" * 70)
    print("下载后处理")
    print("=" * 70)
    print("  1. 解压下载的 tar.gz 文件")
    print("  2. 整理到以下目录结构：")
    print(f"\n    {output_dir}/")
    print("    ├── train/")
    print("    │   ├── real/          # 真实人脸帧")
    print("    │   │   ├── 0/         # 人脸序列 0")
    print("    │   │   ├── 1/         # 人脸序列 1")
    print("    │   │   └── ...")
    print("    │   └── fake/          # 伪造人脸帧")
    print("    │       ├── 0/         # 人脸序列 0")
    print("    │       └── ...")
    print("    ├── valid/")
    print("    │   ├── real/")
    print("    │   └── fake/")
    print("    └── test/")
    print("        ├── real/")
    print("        └── fake/")
    
    print("\n" + "=" * 70)
    print("提示")
    print("=" * 70)
    print("  - 数据集已预处理为图片帧，无需额外提取")
    print("  - 每个 tar.gz 文件包含多个人脸序列文件夹")
    print("  - 下载完成后运行：python scripts/organize_wilddeepfake.py")
    print("=" * 70)
    
    # 创建目录结构
    os.makedirs(output_dir, exist_ok=True)
    for split in ['train', 'valid', 'test']:
        for label in ['real', 'fake']:
            os.makedirs(os.path.join(output_dir, split, label), exist_ok=True)
    
    print(f"\n✓ 已创建基础目录结构：{os.path.abspath(output_dir)}")
    return True


def extract_tar_gz(tar_path, output_dir):
    """
    解压 tar.gz 文件
    
    参数:
        tar_path: tar.gz 文件路径
        output_dir: 输出目录
    """
    print(f"正在解压：{tar_path}")
    
    with tarfile.open(tar_path, 'r:gz') as tar:
        tar.extractall(path=output_dir)
    
    print(f"解压完成：{output_dir}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='WildDeepfake 数据集下载和预处理')
    parser.add_argument('--output_dir', type=str, default='data', help='输出目录')
    parser.add_argument('--tar_path', type=str, help='tar.gz 文件路径（用于解压）')
    args = parser.parse_args()
    
    download_wilddeepfake(args.output_dir)
    
    if args.tar_path:
        extract_tar_gz(args.tar_path, args.output_dir)
