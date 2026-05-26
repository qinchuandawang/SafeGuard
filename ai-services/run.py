"""
SafeGuard AI 检测服务 — 统一启动入口

同时启动音频检测 (port 5001) 和视频检测 (port 5002) 两个服务。
在 PyCharm 中只需运行此文件即可。

用法：
    python run.py

可指定端口：
    AUDIO_PORT=5001 VIDEO_PORT=5002 python run.py
"""

import subprocess
import sys
import os
import signal
import atexit

BASE = os.path.dirname(os.path.abspath(__file__))
_processes = []


def _start(name, workdir, script, port, extra_env=None):
    # 优先使用项目虚拟环境（D 盘），避免占 C 盘空间
    venv_python = os.path.join(os.path.dirname(BASE), '.venv', 'Scripts', 'python.exe')
    python_exe = venv_python if os.path.exists(venv_python) else sys.executable
    env = {
        **os.environ,
        'PORT': str(port),
        **(extra_env or {}),
    }
    cwd = os.path.join(BASE, workdir)
    print(f"[{name}] starting → http://localhost:{port}  (cwd={cwd})")
    p = subprocess.Popen([python_exe, '-u', script], cwd=cwd, env=env)
    _processes.append(p)
    return p


def _cleanup():
    for p in _processes:
        try:
            p.terminate()
            p.wait(timeout=3)
        except Exception:
            pass


def main():
    atexit.register(_cleanup)
    _start('audio', 'audio', 'run.py', int(os.environ.get('AUDIO_PORT', 5001)))
    _start('video', 'video', 'api/app.py', int(os.environ.get('VIDEO_PORT', 5002)))

    print('─' * 50)
    print('  AI Services 已启动 (Ctrl+C 停止)')
    print(f'  音频检测 → http://localhost:{os.environ.get("AUDIO_PORT", 5001)}')
    print(f'  视频检测 → http://localhost:{os.environ.get("VIDEO_PORT", 5002)}')
    print('─' * 50)

    def _stop(_sig, _frame):
        print('\n正在停止服务…')
        _cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    try:
        for p in _processes:
            p.wait()
    except KeyboardInterrupt:
        _stop(None, None)


if __name__ == '__main__':
    main()
