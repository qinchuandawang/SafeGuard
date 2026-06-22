/**
 * TaskWatcher - 异步任务监控
 *
 * 支持两种模式（SSE 优先，轮询回退）：
 *   SSE: 对接后端 GET /api/task/{taskId}/stream，实时推送进度
 *   轮询: 定时 GET /api/task/{taskId}，作为兜底方案
 *
 * 使用场景：音频/视频上传后，等待 AI 模型检测完成
 */

const app = getApp();

class TaskWatcher {
  constructor(options = {}) {
    this.pollInterval = options.pollInterval || 1500;
    this.baseUrl = options.baseUrl || app.globalData.apiBaseUrl;
    this.preferPolling = options.preferPolling !== false;
    this._sseTask = null;
    this._destroyed = false;
    this._pollAttempts = 0;
    this._maxPollAttempts = options.maxPollAttempts || 120;
    this._lastPollError = null;
  }

  /**
   * 开始监控任务
   * @param {string} taskId
   * @param {Object} callbacks - { onProgress, onDone, onError }
   */
  watch(taskId, callbacks = {}) {
    const { onProgress, onDone, onError } = callbacks;
    this._destroyed = false;

    if (this.preferPolling) {
      this._startPolling(taskId, { onProgress, onDone, onError });
      return;
    }

    // 优先尝试 SSE
    const sseStarted = this._startSSE(taskId, { onProgress, onDone, onError });
    if (!sseStarted) {
      // SSE 不可用时自动回退到轮询
      this._startPolling(taskId, { onProgress, onDone, onError });
    }
  }

  /** SSE 模式：GET /api/detection/task/{taskId}/stream */
  _startSSE(taskId, { onProgress, onDone, onError }) {
    try {
      const authModule = require('../utils/auth');
      const url = `${this.baseUrl}/api/detection/task/${taskId}/stream`;
      const requestTask = wx.request({
        url,
        method: 'GET',
        enableChunked: true,
        header: {
          ...authModule.getAuthHeader(),
        },
        success: () => {},
        fail: () => {},
      });

      let buffer = '';
      let hasHandledEvent = false;
      requestTask.onChunkReceived((res) => {
        if (this._destroyed) return;

        try {
          const rawBytes = res.data;
          let text;
          if (typeof TextDecoder !== 'undefined') {
            text = new TextDecoder('utf-8').decode(rawBytes);
          } else {
            text = String.fromCharCode.apply(null, new Uint8Array(rawBytes));
          }
          buffer += text;

          const blocks = buffer.split('\n\n');
          buffer = blocks.pop() || '';

          for (const block of blocks) {
            const parsed = this._parseSseBlock(block);
            if (!parsed) continue;
            hasHandledEvent = true;
            this._handleEvent(parsed, { onProgress, onDone, onError });
          }
        } catch (err) {
          console.warn('[TaskWatcher] SSE 解析异常:', err);
        }
      });

      this._sseTask = requestTask;

      // 5 秒后检查是否解析到有效事件，没解析到则回退轮询
      setTimeout(() => {
        if (!hasHandledEvent && !this._destroyed) {
          console.warn('[TaskWatcher] SSE 无有效事件，回退到轮询模式');
          this._destroyed = false;
          this._sseTask = null;
          this._startPolling(taskId, { onProgress, onDone, onError });
        }
      }, 5000);

      return true;
    } catch (err) {
      console.warn('[TaskWatcher] SSE 启动失败，回退轮询:', err);
      return false;
    }
  }

  _parseSseBlock(block) {
    if (!block) return null;
    let eventName = '';
    const dataLines = [];
    const lines = block.split('\n');
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim());
      }
    }
    if (!dataLines.length) return null;
    const payload = dataLines.join('\n');
    if (payload === '[DONE]') return null;
    try {
      const data = JSON.parse(payload);
      if (eventName && !data.event) data.event = eventName;
      return data;
    } catch (_) {
      return eventName ? { event: eventName, message: payload } : null;
    }
  }

  /** 处理 SSE 事件分发 */
  _handleEvent(data, { onProgress, onDone, onError }) {
    const { event, status, progress, result, error } = data;
    const evt = event || status;

    switch (evt) {
      case 'progress':
      case 'processing':
        onProgress?.(progress || 0, data);
        break;
      case 'done':
      case 'completed':
        onDone?.(result || data);
        this.destroy();
        break;
      case 'failed':
      case 'error':
        onError?.(new Error(error || '任务处理失败'));
        this.destroy();
        break;
    }
  }

  /** 轮询模式：定时 GET /api/detection/task/{taskId} */
  async _startPolling(taskId, { onProgress, onDone, onError }) {
    const url = `${this.baseUrl}/api/detection/task/${taskId}`;
    this._pollAttempts = 0;
    this._lastPollError = null;

    while (!this._destroyed) {
      this._pollAttempts++;
      if (this._pollAttempts > this._maxPollAttempts) {
        const suffix = this._lastPollError ? `，最后一次错误：${this._lastPollError}` : '';
        onError?.(new Error(`任务查询超时${suffix}`));
        this.destroy();
        return;
      }

      try {
        const res = await this._request(url);
        if (this._destroyed) break;

        if (res && typeof res.code === 'number' && res.code !== 200) {
          onError?.(new Error(res.message || '任务查询失败'));
          this.destroy();
          return;
        }
        const taskData = this._unwrapResult(res);

        switch (taskData.status) {
          case 'queued':
          case 'processing':
            onProgress?.(taskData.progress || 0, taskData);
            await this._sleep(this.pollInterval);
            break;
          case 'done':
          case 'completed':
            onDone?.(taskData.result || taskData);
            this.destroy();
            return;
          case 'failed':
          case 'error':
            onError?.(new Error(this._buildTaskError(taskData)));
            this.destroy();
            return;
          default:
            await this._sleep(this.pollInterval);
        }
      } catch (err) {
        if (!this._destroyed) {
          // 单次轮询失败不直接报错，继续重试（避免后端繁忙时误判）
          this._lastPollError = this._formatError(err);
          await this._sleep(this.pollInterval);
          continue;
        }
        return;
      }
    }
  }

  /** 封装 wx.request */
  _request(url) {
    const authHeader = require('../utils/auth').getAuthHeader();
    return new Promise((resolve, reject) => {
      wx.request({
        url,
        method: 'GET',
        timeout: 10000,
        header: {
          'Content-Type': 'application/json',
          ...authHeader,
        },
        success: (res) => resolve(res.data),
        fail: reject,
      });
    });
  }

  _unwrapResult(res) {
    if (!res) return {};
    if (res.code === 200 && res.data) return res.data;
    if (res.data && res.data.status) return res.data;
    return res;
  }

  _buildTaskError(taskData) {
    const rawMessage = taskData.error || taskData.message || '任务处理失败';
    return this._formatServiceError(rawMessage);
  }

  _formatError(err) {
    if (!err) return '未知错误';
    const rawMessage = err.errMsg || err.message || String(err);
    return this._formatServiceError(rawMessage);
  }

  _formatServiceError(rawMessage) {
    const message = String(rawMessage || '未知错误');
    if (message.includes('localhost:5002') || message.includes('127.0.0.1:5002')) {
      return '视频检测服务未启动或不可访问，请确认 SafeGuard-Video-Service 窗口正在运行，并检查 http://localhost:5002/api/health';
    }
    if (message.includes('localhost:5000') || message.includes('127.0.0.1:5000')) {
      return '音频检测服务未启动或不可访问，请确认 SafeGuard-Audio-Service 窗口正在运行，并检查 http://localhost:5000/health';
    }
    if (message.includes('timeout')) {
      return `请求超时：${message}`;
    }
    return message;
  }

  /** 停止监控，释放资源 */
  destroy() {
    this._destroyed = true;
    if (this._sseTask) {
      this._sseTask.abort();
      this._sseTask = null;
    }
  }

  _sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}

module.exports = TaskWatcher;
