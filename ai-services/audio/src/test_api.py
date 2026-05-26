import argparse
import json
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser(description="本地测试 Flask 音频检测接口")
    parser.add_argument("--url", type=str, default="http://127.0.0.1:5000/audio/detect")
    parser.add_argument("--audio_path", type=str, required=True)
    args = parser.parse_args()

    audio_path = Path(args.audio_path)
    if not audio_path.exists():
        raise FileNotFoundError(f"audio_path 不存在: {audio_path}")

    with audio_path.open("rb") as f:
        files = {"file": (audio_path.name, f)}  # requests 自动根据扩展名推断 MIME
        resp = requests.post(args.url, files=files, timeout=120)

    print(f"status_code: {resp.status_code}")
    try:
        print(json.dumps(resp.json(), ensure_ascii=False, indent=2))
    except Exception:
        print(resp.text)


if __name__ == "__main__":
    main()



