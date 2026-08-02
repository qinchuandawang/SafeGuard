"""
SafeGuard 数据集准备脚本。

说明：
1. ASVspoof、FaceForensics++、DFDC 等数据集通常需要注册、同意协议或 Kaggle Token。
2. 本脚本负责创建标准目录、写入下载说明，并在提供直链或 Kaggle 环境时执行下载。
3. 不把大规模训练数据提交到仓库，避免污染 Git 历史和违反数据集协议。
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = ROOT / "datasets"


@dataclass
class DatasetSpec:
    name: str
    category: str
    target: str
    url: str
    purpose: str
    requires_auth: bool = True
    kaggle_ref: str | None = None


DATASETS = [
    DatasetSpec(
        name="ASVspoof2019 LA",
        category="audio",
        target="audio/asvspoof2019_la",
        url="https://datashare.ed.ac.uk/handle/10283/3336",
        purpose="音频语音合成与转换攻击检测训练/评测。",
    ),
    DatasetSpec(
        name="ASVspoof2021 LA/DF",
        category="audio",
        target="audio/asvspoof2021",
        url="https://www.asvspoof.org/index2021.html",
        purpose="更贴近真实语音克隆和深度伪造的音频评测。",
    ),
    DatasetSpec(
        name="FaceForensics++",
        category="video",
        target="video/faceforensicspp",
        url="https://github.com/ondyari/FaceForensics",
        purpose="DeepFake、Face2Face、FaceSwap 等视频换脸检测训练。",
    ),
    DatasetSpec(
        name="DFDC",
        category="video",
        target="video/dfdc",
        url="https://www.kaggle.com/c/deepfake-detection-challenge/data",
        purpose="大规模真实世界 Deepfake 视频检测评测。",
        kaggle_ref="c/deepfake-detection-challenge",
    ),
]


def write_manifest() -> None:
    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    manifest = DATA_ROOT / "manifest.json"
    manifest.write_text(
        json.dumps([asdict(item) for item in DATASETS], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def prepare_dirs() -> None:
    for item in DATASETS:
        target = DATA_ROOT / item.target
        target.mkdir(parents=True, exist_ok=True)
        readme = target / "README.md"
        readme.write_text(
            "\n".join(
                [
                    f"# {item.name}",
                    "",
                    f"- 类别：{item.category}",
                    f"- 用途：{item.purpose}",
                    f"- 官方地址：{item.url}",
                    f"- 需要授权：{'是' if item.requires_auth else '否'}",
                    "",
                    "请按官方协议下载数据，并将解压后的原始数据放在当前目录。",
                ]
            ),
            encoding="utf-8",
        )


def try_kaggle_download() -> None:
    kaggle = shutil.which("kaggle")
    if not kaggle:
        return
    if not os.environ.get("KAGGLE_USERNAME") and not (Path.home() / ".kaggle" / "kaggle.json").exists():
        return
    for item in DATASETS:
        if not item.kaggle_ref:
            continue
        target = DATA_ROOT / item.target
        print(f"[Kaggle] 下载 {item.name} 到 {target}")
        subprocess.run(
            [kaggle, "competitions", "download", "-c", item.kaggle_ref.split("/")[-1], "-p", str(target)],
            check=False,
        )


def main() -> None:
    write_manifest()
    prepare_dirs()
    try_kaggle_download()
    print(f"数据集目录已准备完成：{DATA_ROOT}")
    print("受限数据集请按各自 README.md 的官方地址完成授权下载。")


if __name__ == "__main__":
    main()
