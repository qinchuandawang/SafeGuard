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
    this._sseTask = null;
    this._destroyed = false;
    this._pollAttempts = 0;
    this._maxPollAttempts = options.maxPollAttempts || 120;
  }

  /**
   * 开始监控任务
   * @param {string} taskId
   * @param {Object} callbacks - { onProgress, onDone, onError }
   */
  watch(taskId, callbacks = {}) {
    const { onProgress, onDone, onError } = callbacks;
    this._destroyed = false;

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
      let hasReceived = false;
      requestTask.onChunkReceived((res) => {
        if (this._destroyed) return;
        hasReceived = true;

        try {
          const rawBytes = res.data;
          let text;
          if (typeof TextDecoder !== 'undefined') {
            text = new TextDecoder('utf-8').decode(rawBytes);
          } else {
            text = String.fromCharCode.apply(null, new Uint8Array(rawBytes));
          }
          buffer += text;

          // 按行解析 SSE 事件
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (!line.startsWith('data: ')) continue;
            const payload = line.slice(6).trim();
            if (payload === '[DONE]') continue;

            try {
              const data = typeof payload === 'string' ? JSON.parse(payload) : payload;
              this._handleEvent(data, { onProgress, onDone, onError });
            } catch (_) {
              // 非 JSON 数据忽略
            }
          }
        } catch (err) {
          console.warn('[TaskWatcher] SSE 解析异常:', err);
        }
      });

      this._sseTask = requestTask;

      // 5 秒后检查是否收到数据，没收到说明 SSE 连接失败，回退轮询
      setTimeout(() => {
        if (!hasReceived && !this._destroyed) {
          console.warn('[TaskWatcher] SSE 无数据响应，回退到轮询模式');
          this.destroy();
          this._startPolling(taskId, { onProgress, onDone, onError });
        }
      }, 5000);

      return true;
    } catch (err) {
      console.warn('[TaskWatcher] SSE 启动失败，回退轮询:', err);
      return false;
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

    while (!this._destroyed) {
      this._pollAttempts++;
      if (this._pollAttempts > this._maxPollAttempts) {
        onError?.(new Error('轮询超时'));
        this.destroy();
        return;
      }

      try {
        const res = await this._request(url);
        if (this._destroyed) break;

        const taskData = res.data || res;

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
            onError?.(new Error(taskData.error || '任务处理失败'));
            this.destroy();
            return;
          default:
            await this._sleep(this.pollInterval);
        }
      } catch (err) {
        if (!this._destroyed) {
          onError?.(err);
          this.destroy();
          return;
        }
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
