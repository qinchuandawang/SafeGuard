import argparse
import random
from collections import Counter
from pathlib import Path


def parse_protocol_line(line: str):
    """
    ASVspoof2019 LA CM protocol 常见格式：
    speaker_id utt_id - attack_id label
    例如：
    LA_0079 LA_T_1138215 - A07 spoof
    """
    parts = line.strip().split()
    if len(parts) < 2:
        return None

    utt_id = parts[1]

    label = None
    for token in reversed(parts):
        if token in {"bonafide", "spoof"}:
            label = token
            break

    if label is None:
        return None

    return utt_id, label


def read_protocol(protocol_path: Path):
    items = []
    bad_lines = 0

    with protocol_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parsed = parse_protocol_line(line)
            if parsed is None:
                bad_lines += 1
                continue
            items.append(parsed)

    return items, bad_lines


def detect_split_from_protocol_name(name: str):
    lowered = name.lower()
    if ".train." in lowered:
        return "train"
    if ".dev." in lowered:
        return "dev"
    if ".eval." in lowered:
        return "eval"
    return None


def get_audio_root(data_dir: Path, split: str):
    return data_dir / f"ASVspoof2019_LA_{split}" / "flac"


def check_split(data_dir: Path, protocol_path: Path, sample_size: int):
    items, bad_lines = read_protocol(protocol_path)
    split = detect_split_from_protocol_name(protocol_path.name)

    print(f"\n=== {protocol_path.name} ===")
    print(f"parsed entries: {len(items)}")
    print(f"bad lines: {bad_lines}")

    if split is None:
        print("[WARN] 无法从协议文件名识别 split(train/dev/eval)，跳过音频匹配检查")
        return

    audio_root = get_audio_root(data_dir, split)
    if not audio_root.exists():
        print(f"[ERROR] 音频目录不存在: {audio_root}")
        return

    label_counter = Counter(label for _, label in items)
    print(f"label distribution: {dict(label_counter)}")

    missing = []
    for utt_id, _ in items:
        audio_file = audio_root / f"{utt_id}.flac"
        if not audio_file.exists():
            missing.append(str(audio_file))

    print(f"audio root: {audio_root}")
    print(f"missing files: {len(missing)}")

    if missing:
        print("missing examples (up to 10):")
        for p in missing[:10]:
            print(f"  - {p}")

    sample_n = min(sample_size, len(items))
    if sample_n > 0:
        sampled = random.sample(items, sample_n)
        zero_size = 0
        for utt_id, _ in sampled:
            fp = audio_root / f"{utt_id}.flac"
            if fp.exists() and fp.stat().st_size == 0:
                zero_size += 1
        print(f"sample size check: {sample_n}, zero-size files in sample: {zero_size}")


def main():
    parser = argparse.ArgumentParser(description="检查 ASVspoof2019 LA 数据完整性与协议匹配情况")
    parser.add_argument("--data_dir", type=str, default="data", help="数据根目录")
    parser.add_argument("--sample_size", type=int, default=50, help="每个 split 抽样检查的音频数")
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    cm_protocol_dir = data_dir / "ASVspoof2019_LA_cm_protocols"

    if not data_dir.exists():
        raise FileNotFoundError(f"data_dir 不存在: {data_dir}")
    if not cm_protocol_dir.exists():
        raise FileNotFoundError(f"CM protocol 目录不存在: {cm_protocol_dir}")

    protocol_files = sorted(cm_protocol_dir.glob("ASVspoof2019.LA.cm.*.txt"))
    if not protocol_files:
        raise FileNotFoundError(f"未找到 CM protocol 文件: {cm_protocol_dir}")

    print(f"data_dir: {data_dir.resolve()}")
    print(f"cm_protocol_dir: {cm_protocol_dir.resolve()}")

    for pf in protocol_files:
        check_split(data_dir, pf, args.sample_size)

    print("\n检查完成。")


if __name__ == "__main__":
    main()
