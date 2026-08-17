"""
SafeGuard AI 检测服务 — 统一启动入口

同时启动音频检测、视频检测和 LangGraph 编排服务。
在 PyCharm 中只需运行此文件即可。

用法：
    python run.py

可指定端口：
    AUDIO_PORT=5000 VIDEO_PORT=5002 ORCHESTRATOR_PORT=5003 python run.py
"""

import subprocess
import sys
import os
import signal
import atexit

BASE = os.path.dirname(os.path.abspath(__file__))
_processes = []


def _start(name, workdir, script, port, extra_env=None):
    # 优先使用项目虚拟环境，避免占 C 盘空间
    # 兼容两种常见放置位置：
    # 1) ai-services/.venv
    # 2) repo_root/.venv
    venv_candidates = [
        os.path.join(BASE, ".venv", "Scripts", "python.exe"),
        os.path.join(os.path.dirname(BASE), ".venv", "Scripts", "python.exe"),
    ]
    python_exe = next((p for p in venv_candidates if os.path.exists(p)), sys.executable)
    env = {
        **os.environ,
        'PORT': str(port),
        **(extra_env or {}),
    }
    cwd = os.path.join(BASE, workdir)
    print(f"[{name}] starting → http://localhost:{port}  (cwd={cwd})")
    command = (
        [python_exe, '-u', '-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', str(port)]
        if name == 'orchestrator'
        else [python_exe, '-u', script]
    )
    p = subprocess.Popen(command, cwd=cwd, env=env)
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
    _start('audio', 'audio', 'app.py', int(os.environ.get('AUDIO_PORT', 5000)))
    _start('video', 'video', 'api/app.py', int(os.environ.get('VIDEO_PORT', 5002)))
    if os.environ.get('ORCHESTRATOR_ENABLED', 'true').lower() == 'true':
        _start(
            'orchestrator', 'orchestrator', '-m', int(os.environ.get('ORCHESTRATOR_PORT', 5003)),
            {
                'AUDIO_DETECTION_URL': f'http://localhost:{os.environ.get("AUDIO_PORT", 5000)}/audio/detect',
                'VIDEO_DETECTION_URL': f'http://localhost:{os.environ.get("VIDEO_PORT", 5002)}/api/detect/video',
            },
        )

    print('─' * 50)
    print('  AI Services 已启动 (Ctrl+C 停止)')
    print(f'  音频检测 → http://localhost:{os.environ.get("AUDIO_PORT", 5000)}')
    print(f'  视频检测 → http://localhost:{os.environ.get("VIDEO_PORT", 5002)}')
    if os.environ.get('ORCHESTRATOR_ENABLED', 'true').lower() == 'true':
        print(f'  AI 编排   → http://localhost:{os.environ.get("ORCHESTRATOR_PORT", 5003)}')
    print('─' * 50)

    def _stop(_sig, _frame):
        print('\n正在停止服务…')
        _cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, _stop)
    # Windows 不支持 SIGTERM，仅在非 Windows 平台注册
    if hasattr(signal, 'SIGTERM'):
        signal.signal(signal.SIGTERM, _stop)

    try:
        for p in _processes:
            p.wait()
    except KeyboardInterrupt:
        _stop(None, None)


if __name__ == '__main__':
    main()
