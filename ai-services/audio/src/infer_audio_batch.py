"""
批量音频推理 + 评估脚本

用法：
    # 仅推理
    py -3.11 infer_audio_batch.py \\
        --model_dir outputs/wav2vec2-mid/best \\
        --audio_dir data/ASVspoof2019_LA_dev/flac \\
        --glob "*.flac" \\
        --output_csv outputs/dev_batch_results.csv

    # 推理+评估
    py -3.11 infer_audio_batch.py \\
        --model_dir outputs/wav2vec2-mid/best \\
        --audio_dir data/ASVspoof2019_LA_dev/flac \\
        --glob "*.flac" \\
        --protocol_path data/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt \\
        --output_csv outputs/dev_batch_results.csv
"""

import argparse
import csv
import json
import time
from pathlib import Path

import numpy as np
import torch
from transformers import Wav2Vec2ForSequenceClassification, Wav2Vec2Processor

from utils import load_audio, get_device, load_model_once


LABEL2ID = {"bonafide": 0, "spoof": 1}
ID2LABEL = {0: "bonafide", 1: "spoof"}


def parse_protocol(protocol_path: Path) -> dict:
    """
    解析 ASVspoof2019 协议文件。

    Returns:
        { utt_id: label_str } 例如 { "LA_D_1047731": "bonafide" }
    """
    if not protocol_path.exists():
        raise FileNotFoundError(f"协议文件不存在: {protocol_path}")

    result = {}
    with protocol_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            utt_id = parts[1]
            label = None
            for token in reversed(parts):
                if token in LABEL2ID:
                    label = token
                    break
            if label:
                result[utt_id] = label
    return result


def main():
    parser = argparse.ArgumentParser(description="批量音频推理")
    parser.add_argument("--model_dir", type=str, default="outputs/wav2vec2-mid/best")
    parser.add_argument("--audio_dir", type=str, required=True)
    parser.add_argument("--glob", type=str, default="*.flac")
    parser.add_argument("--protocol_path", type=str, default=None)
    parser.add_argument("--output_csv", type=str, default="batch_results.csv")
    parser.add_argument("--max_seconds", type=float, default=4.0)
    parser.add_argument("--sample_rate", type=int, default=16000)
    parser.add_argument("--batch_size", type=int, default=32)
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    audio_dir = Path(args.audio_dir)
    protocol_path = Path(args.protocol_path) if args.protocol_path else None
    output_csv = Path(args.output_csv)
    output_csv.parent.mkdir(parents=True, exist_ok=True)

    if not audio_dir.exists():
        raise FileNotFoundError(f"音频目录不存在: {audio_dir}")
    if not model_dir.exists():
        raise FileNotFoundError(f"模型目录不存在: {model_dir}")

    # 加载协议（如果提供）
    protocol = None
    if protocol_path:
        protocol = parse_protocol(protocol_path)
        print(f"[协议] 加载了 {len(protocol)} 条标签")

    # 加载模型
    processor, model = load_model_once(model_dir)
    device = get_device()

    # 收集音频文件
    audio_files = sorted(audio_dir.glob(args.glob))
    if not audio_files:
        raise FileNotFoundError(f"在 {audio_dir} 中未找到匹配 {args.glob} 的文件")

    print(f"[数据] 共 {len(audio_files)} 个音频文件")

    # 批量推理
    results = []
    total = len(audio_files)
    t_start = time.time()

    for i in range(0, total, args.batch_size):
        batch_files = audio_files[i : i + args.batch_size]

        # 加载 batch 内所有音频
        batch_wavs = []
        batch_keys = []
        for audio_path in batch_files:
            wav = load_audio(audio_path, args.sample_rate, args.max_seconds)
            batch_wavs.append(wav)
            batch_keys.append(audio_path.stem)

        # 通过 processor 统一 padding 成 batch
        inputs = processor(
            batch_wavs, sampling_rate=args.sample_rate,
            return_tensors="pt", padding=True,
        )
        inputs = {k: v.to(device) for k, v in inputs.items()}

        # 单次前向传播处理整个 batch
        with torch.no_grad():
            logits = model(**inputs).logits
            probs = torch.softmax(logits, dim=-1).cpu().numpy()

        batch_results = []
        for j, utt_key in enumerate(batch_keys):
            pred_id = int(np.argmax(probs[j]))
            label = ID2LABEL[pred_id]
            true_label = protocol.get(utt_key, "") if protocol else ""

            batch_results.append({
                "utt_id": utt_key,
                "file": str(batch_files[j]),
                "status": "ok",
                "pred_label": label,
                "true_label": true_label,
                "spoof_prob": float(probs[j][1]),
                "bonafide_prob": float(probs[j][0]),
                "confidence": float(probs[j][pred_id]),
            })

        results.extend(batch_results)

        # 打印进度
        pct = min(100, (i + len(batch_files)) / total * 100)
        elapsed = time.time() - t_start
        rate = (i + len(batch_files)) / elapsed if elapsed > 0 else 0
        print(f"\r[进度] {i + len(batch_files)}/{total} ({pct:.1f}%) | "
              f"耗时 {elapsed:.0f}s | {rate:.1f}条/s", end="")

    print()

    # 写 CSV
    fieldnames = ["utt_id", "file", "status", "pred_label", "true_label",
                  "spoof_prob", "bonafide_prob", "confidence"]
    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)

    total_time = time.time() - t_start
    print(f"[完成] 结果已保存至: {output_csv}")
    print(f"[统计] 共 {total} 条，耗时 {total_time:.1f}s，平均 {total/total_time:.1f} 条/s")

    # 如果有标签，输出评估指标
    if protocol:
        correct = sum(1 for r in results if r["true_label"] and r["pred_label"] == r["true_label"])
        total_labeled = sum(1 for r in results if r["true_label"])
        if total_labeled > 0:
            acc = correct / total_labeled * 100
            print(f"[评估] 准确率: {correct}/{total_labeled} = {acc:.2f}%")


if __name__ == "__main__":
    main()
