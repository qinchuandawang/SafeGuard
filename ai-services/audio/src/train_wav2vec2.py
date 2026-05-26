import argparse
import os
import random
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F
from torch.utils.data import Dataset
from transformers import (
    Trainer,
    TrainingArguments,
    Wav2Vec2ForSequenceClassification,
    Wav2Vec2Processor,
)


LABEL2ID = {"bonafide": 0, "spoof": 1}
ID2LABEL = {0: "bonafide", 1: "spoof"}


def parse_protocol(protocol_path: Path) -> List[Tuple[str, int]]:
    items: List[Tuple[str, int]] = []
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
            if label is None:
                continue
            items.append((utt_id, LABEL2ID[label]))
    return items


def load_audio(path: Path, target_sr: int = 16000) -> np.ndarray:
    wav, sr = sf.read(path)
    if wav.ndim > 1:
        wav = wav.mean(axis=1)
    wav = wav.astype(np.float32)

    if sr != target_sr:
        duration = len(wav) / sr
        target_len = int(duration * target_sr)
        if target_len > 1:
            try:
                from scipy.signal import resample
                wav = resample(wav, target_len).astype(np.float32)
            except ImportError:
                x_old = np.linspace(0, 1, num=len(wav), endpoint=False)
                x_new = np.linspace(0, 1, num=target_len, endpoint=False)
                wav = np.interp(x_new, x_old, wav).astype(np.float32)
    return wav


class ASVspoofDataset(Dataset):
    def __init__(
        self,
        pairs: List[Tuple[str, int]],
        audio_root: Path,
        max_seconds: float = 4.0,
        sample_rate: int = 16000,
    ):
        self.pairs = pairs
        self.audio_root = audio_root
        self.max_len = int(max_seconds * sample_rate)
        self.sample_rate = sample_rate

    def __len__(self):
        return len(self.pairs)

    def __getitem__(self, idx):
        utt_id, label = self.pairs[idx]
        audio_path = self.audio_root / f"{utt_id}.flac"
        wav = load_audio(audio_path, target_sr=self.sample_rate)

        if len(wav) > self.max_len:
            wav = wav[: self.max_len]

        return {
            "input_values": wav,
            "labels": label,
        }


class DataCollatorCTCWithPadding:
    def __init__(self, processor: Wav2Vec2Processor):
        self.processor = processor

    def __call__(self, features):
        input_values = [f["input_values"] for f in features]
        labels = torch.tensor([f["labels"] for f in features], dtype=torch.long)
        batch = self.processor(
            input_values,
            sampling_rate=16000,
            return_tensors="pt",
            padding=True,
        )
        batch["labels"] = labels
        return batch


class WeightedTrainer(Trainer):
    def __init__(self, class_weights: Optional[torch.Tensor] = None, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.class_weights = class_weights

    def compute_loss(self, model, inputs, return_outputs=False, **kwargs):
        labels = inputs.pop("labels")
        outputs = model(**inputs)
        logits = outputs.logits

        if self.class_weights is not None:
            loss = F.cross_entropy(logits, labels, weight=self.class_weights.to(logits.device))
        else:
            loss = F.cross_entropy(logits, labels)

        return (loss, outputs) if return_outputs else loss


def build_compute_metrics_fn(decision_threshold: float):
    def compute_metrics(eval_pred):
        logits, labels = eval_pred
        probs = torch.softmax(torch.tensor(logits), dim=-1).numpy()
        preds = (probs[:, 1] >= decision_threshold).astype(np.int64)

        acc = float((preds == labels).mean())

        tp_spoof = int(((preds == 1) & (labels == 1)).sum())
        fp_spoof = int(((preds == 1) & (labels == 0)).sum())
        fn_spoof = int(((preds == 0) & (labels == 1)).sum())
        precision_spoof = tp_spoof / (tp_spoof + fp_spoof + 1e-9)
        recall_spoof = tp_spoof / (tp_spoof + fn_spoof + 1e-9)
        f1_spoof = 2 * precision_spoof * recall_spoof / (precision_spoof + recall_spoof + 1e-9)

        tp_bona = int(((preds == 0) & (labels == 0)).sum())
        fp_bona = int(((preds == 0) & (labels == 1)).sum())
        fn_bona = int(((preds == 1) & (labels == 0)).sum())
        precision_bona = tp_bona / (tp_bona + fp_bona + 1e-9)
        recall_bona = tp_bona / (tp_bona + fn_bona + 1e-9)
        f1_bona = 2 * precision_bona * recall_bona / (precision_bona + recall_bona + 1e-9)

        return {
            "accuracy": acc,
            "f1": float(f1_spoof),
            "precision_spoof": float(precision_spoof),
            "recall_spoof": float(recall_spoof),
            "f1_spoof": float(f1_spoof),
            "precision_bonafide": float(precision_bona),
            "recall_bonafide": float(recall_bona),
            "f1_bonafide": float(f1_bona),
            "cm_tp": tp_spoof,
            "cm_tn": tp_bona,
            "cm_fp": fp_spoof,
            "cm_fn": fn_spoof,
            "pred_spoof_count": int((preds == 1).sum()),
            "pred_bonafide_count": int((preds == 0).sum()),
            "true_spoof_count": int((labels == 1).sum()),
            "true_bonafide_count": int((labels == 0).sum()),
            "decision_threshold": float(decision_threshold),
        }

    return compute_metrics


def compute_class_weights(train_pairs: List[Tuple[str, int]]) -> torch.Tensor:
    labels = np.array([y for _, y in train_pairs], dtype=np.int64)
    count_bona = max(1, int((labels == 0).sum()))
    count_spoof = max(1, int((labels == 1).sum()))
    total = count_bona + count_spoof

    w_bona = total / (2.0 * count_bona)
    w_spoof = total / (2.0 * count_spoof)
    return torch.tensor([w_bona, w_spoof], dtype=torch.float32)


def main():
    parser = argparse.ArgumentParser(description="ASVspoof2019 LA + Wav2Vec2 训练脚本（优化版）")
    parser.add_argument("--data_dir", type=str, default="data")
    parser.add_argument("--model_dir", type=str, default="pretrained/wav2vec2-base")
    parser.add_argument("--output_dir", type=str, default="outputs/wav2vec2-run")
    parser.add_argument("--max_train_samples", type=int, default=0, help="0表示使用全部")
    parser.add_argument("--max_dev_samples", type=int, default=0, help="0表示使用全部")
    parser.add_argument("--max_seconds", type=float, default=4.0)
    parser.add_argument("--batch_size", type=int, default=4)
    parser.add_argument("--grad_accum_steps", type=int, default=1)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=1e-5)
    parser.add_argument("--weight_decay", type=float, default=0.01)
    parser.add_argument("--warmup_ratio", type=float, default=0.1)
    parser.add_argument("--decision_threshold", type=float, default=0.5)
    parser.add_argument("--save_total_limit", type=int, default=2)
    parser.add_argument("--use_class_weight", action="store_true", help="启用类别不平衡加权损失")
    parser.add_argument("--metric_for_best_model", type=str, default="f1", help="例如 f1 / accuracy / f1_bonafide")
    parser.add_argument("--num_workers", type=int, default=4, help="DataLoader 进程数，CPU读取音频加速")
    parser.add_argument("--eval_strategy", type=str, default="epoch", choices=["no", "steps", "epoch"], help="评估频率策略")
    parser.add_argument("--save_strategy", type=str, default="epoch", choices=["no", "steps", "epoch"], help="checkpoint保存策略")
    parser.add_argument("--logging_steps", type=int, default=10)
    parser.add_argument("--fp16", action="store_true", help="启用FP16混合精度（需CUDA）")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    data_dir = Path(args.data_dir)
    train_proto = data_dir / "ASVspoof2019_LA_cm_protocols" / "ASVspoof2019.LA.cm.train.trn.txt"
    dev_proto = data_dir / "ASVspoof2019_LA_cm_protocols" / "ASVspoof2019.LA.cm.dev.trl.txt"
    train_audio = data_dir / "ASVspoof2019_LA_train" / "flac"
    dev_audio = data_dir / "ASVspoof2019_LA_dev" / "flac"

    train_pairs = parse_protocol(train_proto)
    dev_pairs = parse_protocol(dev_proto)
    random.shuffle(train_pairs)
    random.shuffle(dev_pairs)

    if args.max_train_samples > 0:
        train_pairs = train_pairs[: args.max_train_samples]
    if args.max_dev_samples > 0:
        dev_pairs = dev_pairs[: args.max_dev_samples]

    print(f"train samples: {len(train_pairs)}")
    print(f"dev samples: {len(dev_pairs)}")

    model_dir = os.path.abspath(args.model_dir)
    processor = Wav2Vec2Processor.from_pretrained(model_dir)
    model = Wav2Vec2ForSequenceClassification.from_pretrained(
        model_dir,
        num_labels=2,
        label2id=LABEL2ID,
        id2label=ID2LABEL,
        ignore_mismatched_sizes=True,
    )
    model.freeze_feature_encoder()

    class_weights = None
    if args.use_class_weight:
        class_weights = compute_class_weights(train_pairs)
        print(f"class weights: bonafide={class_weights[0]:.4f}, spoof={class_weights[1]:.4f}")

    train_ds = ASVspoofDataset(train_pairs, train_audio, max_seconds=args.max_seconds)
    dev_ds = ASVspoofDataset(dev_pairs, dev_audio, max_seconds=args.max_seconds)
    collator = DataCollatorCTCWithPadding(processor)

    use_fp16 = args.fp16 and torch.cuda.is_available()

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum_steps,
        learning_rate=args.lr,
        weight_decay=args.weight_decay,
        warmup_ratio=args.warmup_ratio,
        num_train_epochs=args.epochs,
        eval_strategy=args.eval_strategy,
        save_strategy=args.save_strategy,
        save_total_limit=args.save_total_limit,
        logging_steps=args.logging_steps,
        load_best_model_at_end=(args.eval_strategy != "no" and args.save_strategy != "no"),
        metric_for_best_model=args.metric_for_best_model,
        greater_is_better=True,
        fp16=use_fp16,
        dataloader_pin_memory=torch.cuda.is_available(),
        dataloader_num_workers=args.num_workers,
        report_to="none",
    )

    trainer = WeightedTrainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=dev_ds,
        data_collator=collator,
        compute_metrics=build_compute_metrics_fn(args.decision_threshold),
        class_weights=class_weights,
    )

    trainer.train()
    metrics = trainer.evaluate()
    print("final eval metrics:", metrics)

    best_dir = Path(args.output_dir) / "best"
    best_dir.mkdir(parents=True, exist_ok=True)
    trainer.save_model(str(best_dir))
    processor.save_pretrained(str(best_dir))
    print(f"saved best model to: {best_dir}")


if __name__ == "__main__":
    main()
