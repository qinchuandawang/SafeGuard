"""
SafeGuard 音频检测服务 - 入口
将 src/ 加入 Python 路径后启动 Flask 服务。
"""
import os, sys
from pathlib import Path

SRC = str(Path(__file__).parent / "src")
if SRC not in sys.path:
    sys.path.insert(0, SRC)

# 通过 importlib 加载 src/app.py，避免模块名冲突
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
