"""
兼容入口：与旧版 PyCharm 配置兼容。
等同于运行 run.py。
"""
import os, sys
from pathlib import Path

SRC = str(Path(__file__).parent / "src")
if SRC not in sys.path:
    sys.path.insert(0, SRC)

import importlib
_mod = importlib.import_module('app')
app = _mod.app

if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    print("=" * 50)
    print("  SafeGuard 音频检测服务")
    print(f"  端口: {port}")
    print("=" * 50)
    app.run(host="0.0.0.0", port=port, debug=False)
