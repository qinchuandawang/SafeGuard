import argparse
import csv
import json
from pathlib import Path

import numpy as np


LABEL2ID = {"bonafide": 0, "spoof": 1}


def load_rows(csv_path: Path):
    rows = []
    with csv_path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            # 仅处理有真实标签的行
            true_label = r.get("true_label", "")
            if true_label not in LABEL2ID:
                continue
            try:
                spoof_prob = float(r["spoof_prob"])
            except (ValueError, KeyError):
                continue
            rows.append({"true": LABEL2ID[true_label], "spoof_prob": spoof_prob})
    if not rows:
        print(f"[WARN] CSV 中未找到包含 true_label 的有效的行。")
        print(f"       请确保使用 --protocol_path 参数运行 infer_audio_batch.py。")
    return rows


def metrics_at_threshold(rows, thr: float):
    y_true = np.array([x["true"] for x in rows], dtype=np.int64)
    y_pred = np.array([1 if x["spoof_prob"] >= thr else 0 for x in rows], dtype=np.int64)

    acc = float((y_true == y_pred).mean())

    tp = int(((y_pred == 1) & (y_true == 1)).sum())
    tn = int(((y_pred == 0) & (y_true == 0)).sum())
    fp = int(((y_pred == 1) & (y_true == 0)).sum())
    fn = int(((y_pred == 0) & (y_true == 1)).sum())

    precision_spoof = tp / (tp + fp + 1e-9)
    recall_spoof = tp / (tp + fn + 1e-9)
    f1_spoof = 2 * precision_spoof * recall_spoof / (precision_spoof + recall_spoof + 1e-9)

    tp_bona = tn
    fp_bona = fn
    fn_bona = fp
    precision_bona = tp_bona / (tp_bona + fp_bona + 1e-9)
    recall_bona = tp_bona / (tp_bona + fn_bona + 1e-9)
    f1_bona = 2 * precision_bona * recall_bona / (precision_bona + recall_bona + 1e-9)

    return {
        "threshold": thr,
        "accuracy": acc,
        "precision_spoof": float(precision_spoof),
        "recall_spoof": float(recall_spoof),
        "f1_spoof": float(f1_spoof),
        "precision_bonafide": float(precision_bona),
        "recall_bonafide": float(recall_bona),
        "f1_bonafide": float(f1_bona),
        "cm_tp": tp,
        "cm_tn": tn,
        "cm_fp": fp,
        "cm_fn": fn,
        "pred_spoof_count": int((y_pred == 1).sum()),
        "pred_bonafide_count": int((y_pred == 0).sum()),
        "true_spoof_count": int((y_true == 1).sum()),
        "true_bonafide_count": int((y_true == 0).sum()),
    }


def parse_thresholds(s: str):
    return [float(x.strip()) for x in s.split(",") if x.strip()]


def main():
    parser = argparse.ArgumentParser(description="基于批量推理CSV做阈值扫描")
    parser.add_argument("--input_csv", type=str, required=True, help="infer_audio_batch.py 输出的CSV")
    parser.add_argument("--thresholds", type=str, default="0.35,0.4,0.45,0.5,0.55,0.6")
    parser.add_argument("--optimize_for", type=str, default="f1_spoof", choices=["f1_spoof", "recall_spoof", "accuracy"])
    parser.add_argument("--output_csv", type=str, default="outputs/threshold_scan_results.csv")
    args = parser.parse_args()

    input_csv = Path(args.input_csv)
    if not input_csv.exists():
        raise FileNotFoundError(f"input_csv 不存在: {input_csv}")

    rows = load_rows(input_csv)
    if not rows:
        raise ValueError("CSV 中没有可用于评估的数据。请确认包含 true_label/status/spoof_prob。")

    thresholds = parse_thresholds(args.thresholds)
    results = [metrics_at_threshold(rows, t) for t in thresholds]

    best = max(results, key=lambda x: x[args.optimize_for])

    output_csv = Path(args.output_csv)
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = list(results[0].keys())
    with output_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)

    print("=== threshold scan summary ===")
    print(json.dumps({
        "input_csv": str(input_csv),
        "output_csv": str(output_csv),
        "optimize_for": args.optimize_for,
        "best": best,
        "all_results": results,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
