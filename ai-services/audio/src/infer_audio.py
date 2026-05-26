"""
单条音频推理脚本（ASVspoof bonafide/spoof）

用法：
    py -3.11 infer_audio.py --model_dir outputs/wav2vec2-mid/best --audio_path data/xxx.flac
"""

import argparse
import json
from pathlib import Path

from utils import predict_audio


def main():
    parser = argparse.ArgumentParser(description="单条音频推理")
    parser.add_argument("--model_dir", type=str, default="outputs/wav2vec2-mid/best")
    parser.add_argument("--audio_path", type=str, required=True)
    parser.add_argument("--max_seconds", type=float, default=4.0)
    parser.add_argument("--sample_rate", type=int, default=16000)
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    audio_path = Path(args.audio_path)

    if not audio_path.exists():
        raise FileNotFoundError(f"音频文件不存在: {audio_path}")

    # 推理（predict_audio 内部会自动加载模型）
    result = predict_audio(audio_path, model_dir, args.sample_rate, args.max_seconds)

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
