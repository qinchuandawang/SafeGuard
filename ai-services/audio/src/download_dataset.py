#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ASVspoof2019 LA 数据集下载脚本

数据集来源：
  ASVspoof 2019: A large-scale public database of synthesized, 
  replayed and generated speech (Logical Access Track)

下载命令：
  1. 自动下载（需要 wget）：
     python download_dataset.py --auto
   
  2. 手动下载方式：
     a. 访问 https://zenodo.org/records/3932367
     b. 下载 LA.zip
     c. 将 LA.zip 放入 data/ 目录
     d. 运行 python download_dataset.py --extract-only

输出目录结构：
  audio-training/data/
  ├── ASVspoof2019_LA_train/
  │   └── flac/          # 训练集音频文件（~25,380 个文件）
  ├── ASVspoof2019_LA_dev/
  │   └── flac/          # 开发集音频文件（~24,844 个文件）
  ├── ASVspoof2019_LA_eval/
  │   └── flac/          # 评估集音频文件（~71,237 个文件）
  └── ASVspoof2019_LA_cm_protocols/
      ├── ASVspoof2019.LA.cm.train.trl.txt   # 训练集标签
      ├── ASVspoof2019.LA.cm.dev.trl.txt     # 开发集标签
      └── ASVspoof2019.LA.cm.eval.trl.txt    # 评估集标签

引用：
  @inproceedings{ASVspoof2019,
    author={Todisco, Massimiliano and Wang, Xin and others},
    title={ASVspoof 2019: Future Horizons in Spoofed and Fake Audio Detection},
    booktitle={Interspeech 2019},
    year={2019}
  }
"""

import argparse
import os
import sys
import zipfile
from pathlib import Path

# 数据集根目录
DATA_DIR = Path(__file__).resolve().parent.parent / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)

# 数据集托管在 Edinburgh DataShare（ASVspoof 2019 LA）
# 注意：原始 ASVspoof2019 数据集不在 Zenodo，而是在 Edinburgh DataShare
DATA_URL = "https://datashare.ed.ac.uk/bitstream/handle/10283/3336/LA.zip"


def check_download_tool():
    """检查系统可用的下载工具（优先 curl，其次 wget）。"""
    import shutil
    for tool in ["curl", "wget"]:
        if shutil.which(tool) is not None:
            return tool
    return None


def download_auto():
    """自动下载 LA.zip（约 15GB）。"""
    tool = check_download_tool()
    if tool is None:
        print("错误：未找到 curl 或 wget 工具。")
        print("  请先安装 curl 或 wget，或手动下载 LA.zip。")
        print()
        print("  手动下载方式：")
        print("    1. 访问 https://datashare.ed.ac.uk/handle/10283/3336")
        print("    2. 下载 LA.zip（约 15GB）")
        print(f"    3. 将 LA.zip 放入 {DATA_DIR}")
        print("    4. 运行此脚本加 --extract-only 参数")
        sys.exit(1)

    dest = DATA_DIR / "LA.zip"
    if dest.exists():
        print(f"[跳过] LA.zip 已存在")
        return

    print(f"[下载] LA.zip ...")
    print(f"  来源: {DATA_URL}")
    print(f"  目标: {dest}")
    print("  注意：文件约 15GB，下载需要较长时间...")

    if tool == "curl":
        # -L 跟随重定向, -C - 断点续传, -o 输出文件
        ret = os.system(f'curl -L -C - -o "{dest}" "{DATA_URL}"')
    else:
        ret = os.system(f'wget -c "{DATA_URL}" -O "{dest}"')

    if ret != 0:
        print(f"错误：下载 LA.zip 失败（返回码 {ret}）！")
        sys.exit(1)
    print(f"[完成] LA.zip 下载成功")


def _print_safe(msg: str):
    """安全打印（兼容 Windows GBK 终端）。"""
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode('utf-8', errors='replace').decode('utf-8', errors='replace'))


def _detect_zip_prefix(all_files, extract_dirs):
    """检测 ZIP 中是否包含额外的前缀目录（如 LA/）。"""
    for f in all_files:
        for d in extract_dirs:
            if f.endswith(d):
                return f[: -len(d)]
    return ""


def extract_all():
    """解压 LA.zip 到 data 目录。"""
    la_zip = DATA_DIR / "LA.zip"

    if not la_zip.exists():
        _print_safe(f"错误：{la_zip} 不存在！")
        _print_safe("请先下载 LA.zip 或使用 --auto 参数自动下载。")
        sys.exit(1)

    _print_safe(f"[解压] 正在解压 {la_zip} ...")
    _print_safe("  文件较大，解压可能需要 5-10 分钟...")

    try:
        with zipfile.ZipFile(la_zip, 'r') as zf:
            all_files = zf.namelist()
            _print_safe(f"  共检测到 {len(all_files)} 个文件")

            extract_dirs = [
                "ASVspoof2019_LA_train/",
                "ASVspoof2019_LA_dev/",
                "ASVspoof2019_LA_eval/",
                "ASVspoof2019_LA_cm_protocols/",
            ]

            # 检测 ZIP 内部是否有额外前缀（如 LA/）
            prefix = _detect_zip_prefix(all_files, extract_dirs)
            if prefix:
                _print_safe(f"  检测到 ZIP 内部前缀目录: {prefix}")

            def strip_prefix(name):
                return name[len(prefix):] if prefix and name.startswith(prefix) else name

            files_to_extract = [
                f for f in all_files
                if any(strip_prefix(f).startswith(d) for d in extract_dirs)
                and not f.endswith('/')
            ]

            _print_safe(f"  需要提取 {len(files_to_extract)} 个文件")

            for i, f in enumerate(files_to_extract):
                out_path = strip_prefix(f)
                (DATA_DIR / os.path.dirname(out_path)).mkdir(parents=True, exist_ok=True)
                with zf.open(f) as src, open(DATA_DIR / out_path, 'wb') as dst:
                    dst.write(src.read())
                if (i + 1) % 2000 == 0:
                    _print_safe(f"  解压进度: {i + 1}/{len(files_to_extract)}")

        _print_safe(f"[完成] 解压完毕！")
        _print_safe("")
        _print_safe("解压后的目录结构：")
        for d in extract_dirs:
            full_path = DATA_DIR / d
            if full_path.exists():
                if d == "ASVspoof2019_LA_cm_protocols/":
                    cnt = len(list(full_path.glob("*.txt")))
                else:
                    cnt = len(list(full_path.rglob("*.flac")))
                _print_safe(f"  [OK] {d} ({cnt} 个文件)" if cnt > 0 else f"  [WARN] {d} (无文件)")

        _print_safe("")
        _print_safe("现在可以运行训练命令了！")
        _print_safe("示例: py -3.11 src/train_wav2vec2.py --data_dir data --model_dir pretrained/wav2vec2-base --output_dir outputs/test --max_train_samples 64 --epochs 1")

    except zipfile.BadZipFile:
        _print_safe(f"错误：{la_zip} 不是一个有效的 ZIP 文件！请重新下载。")
        sys.exit(1)
    except Exception as e:
        _print_safe(f"解压失败: {e}")
        sys.exit(1)


def verify_data():
    """验证数据集完整性。"""
    _print_safe("[验证] 检查数据集结构...")

    required_dirs = [
        DATA_DIR / "ASVspoof2019_LA_train" / "flac",
        DATA_DIR / "ASVspoof2019_LA_dev" / "flac",
        DATA_DIR / "ASVspoof2019_LA_eval" / "flac",
    ]

    required_protocols = [
        DATA_DIR / "ASVspoof2019_LA_cm_protocols" / "ASVspoof2019.LA.cm.train.trl.txt",
        DATA_DIR / "ASVspoof2019_LA_cm_protocols" / "ASVspoof2019.LA.cm.dev.trl.txt",
        DATA_DIR / "ASVspoof2019_LA_cm_protocols" / "ASVspoof2019.LA.cm.eval.trl.txt",
    ]

    all_ok = True
    for d in required_dirs:
        if d.exists() and any(d.iterdir()):
            flac_count = len(list(d.glob("*.flac")))
            _print_safe(f"  [OK] {d.parent.name}/flac/ ({flac_count} 个 flac)")
        else:
            _print_safe(f"  [FAIL] {d.parent.name}/flac/ - 缺失或为空")
            all_ok = False

    for p in required_protocols:
        if p.exists():
            _print_safe(f"  [OK] {p.name}")
        else:
            _print_safe(f"  [FAIL] {p.name} - 缺失")
            all_ok = False

    if all_ok:
        _print_safe("")
        _print_safe("数据集完整！可以开始训练。")
    else:
        _print_safe("")
        _print_safe("数据集不完整，请重新下载或解压。")


def main():
    parser = argparse.ArgumentParser(
        description="ASVspoof2019 LA 数据集下载工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例：
  # 自动下载并解压
  python download_dataset.py --auto

  # 仅解压（LA.zip 已提前放入 data/ 目录）
  python download_dataset.py --extract-only

  # 验证数据集完整性
  python download_dataset.py --verify

  # 完整流程
  python download_dataset.py --auto --extract

下载地址：
  Edinburgh DataShare: https://datashare.ed.ac.uk/handle/10283/3336
        """,
    )
    parser.add_argument("--auto", action="store_true", help="自动从 Zenodo 下载数据集")
    parser.add_argument("--extract", "--extract-only", action="store_true", dest="extract", help="只执行解压（不下载）")
    parser.add_argument("--verify", action="store_true", help="验证数据集完整性")
    
    args = parser.parse_args()
    
    if not any([args.auto, args.extract, args.verify]):
        parser.print_help()
        print()
        print("请至少指定一个操作参数。")
        return
    
    if args.auto:
        download_auto()
    
    if args.extract or args.auto:
        extract_all()
    
    if args.verify or args.extract or args.auto:
        verify_data()


if __name__ == "__main__":
    main()
