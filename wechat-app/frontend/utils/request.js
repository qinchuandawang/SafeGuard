// utils/request.js - 网络请求封装（支持认证令牌，无 mock）
// 注意：为避免与 auth.js 循环依赖，auth 使用惰性加载

const CONFIG = require('./config');

const BASE_URL = CONFIG && CONFIG.API_BASE_URL ? CONFIG.API_BASE_URL : '';

function getAuth() {
  return require('./auth');
}

function getTextConversationId() {
  const storageKey = 'safeguard_text_conversation_id';
  let conversationId = wx.getStorageSync(storageKey);
  if (!conversationId) {
    conversationId = `text_${Date.now()}_${Math.random().toString(36).slice(2, 12)}`;
    wx.setStorageSync(storageKey, conversationId);
  }
  return conversationId;
}

// Request queue for cancellation
const requestQueue = new Map();
let requestIdCounter = 0;

function request(options) {
  const {
    url,
    method = 'GET',
    data,
    header = {},
    showLoading = true,
    loadingTitle = '加载中...',
    timeout = 8000,
    retries = 1,
    retryDelay = 1000,
  } = options;

  if (showLoading) {
    wx.showLoading({ title: loadingTitle, mask: true });
  }

  const authModule = getAuth();
  const authHeader = authModule.getAuthHeader();

  const makeRequest = (attempt) => {
    return new Promise((resolve, reject) => {
      const rid = ++requestIdCounter;
      const requestTask = wx.request({
        url: BASE_URL + url,
        method,
        data,
        timeout,
        // 用 arraybuffer 接收响应，绕过 wx 内部按系统编码解码导致的中文乱码
        responseType: 'arraybuffer',
        header: {
          'Content-Type': 'application/json',
          ...authHeader,
          ...header,
        },
        success: (res) => {
          requestQueue.delete(rid);
          if (showLoading) wx.hideLoading();
          if (res.statusCode === 401) {
            authModule.clearAuth();
            wx.showToast({ title: '登录已失效，请重新登录', icon: 'none' });
            reject(Object.assign(new Error('登录已失效'), { statusCode: 401 }));
            return;
          }
          if (res.statusCode === 403) {
            wx.showToast({ title: '没有执行此操作的权限', icon: 'none' });
            reject(Object.assign(new Error('没有权限'), { statusCode: 403 }));
            return;
          }
          if (res.statusCode === 429) {
            wx.showToast({ title: '请求过于频繁，请稍后再试', icon: 'none' });
            reject(new Error('请求频率限制'));
            return;
          }
          if (res.statusCode === 200) {
            // 手动 UTF-8 解码响应（避免 wx 内部按 GBK 解码导致中文乱码）
            // 兼容 res.data 是 ArrayBuffer 或其他类型
            let text;
            if (res.data instanceof ArrayBuffer) {
              const decoder = new TextDecoder('utf-8');
              text = decoder.decode(new Uint8Array(res.data));
            } else if (typeof res.data === 'string') {
              // 某些情况下 responseType 未生效，res.data 仍是字符串
              text = res.data;
            } else {
              // 已经是对象（wx 自动解析了 JSON）
              const { code, message, data: resData } = res.data;
              if (code === 200) {
                resolve(resData);
              } else {
                wx.showToast({ title: message || '请求失败', icon: 'none' });
                reject(new Error(message));
              }
              return;
            }
            let parsed;
            try {
              parsed = JSON.parse(text);
            } catch (e) {
              console.error('响应 JSON 解析失败:', text.substring(0, 200));
              wx.showToast({ title: '响应格式异常', icon: 'none' });
              reject(new Error('解析响应失败'));
              return;
            }
            const { code, message, data: resData } = parsed;
            if (code === 200) {
              resolve(resData);
            } else {
              wx.showToast({ title: message || '请求失败', icon: 'none' });
              reject(new Error(message));
            }
          } else {
            wx.showToast({ title: '服务器错误: ' + res.statusCode, icon: 'none' });
            reject(new Error('请求失败: ' + res.statusCode));
          }
        },
        fail: (err) => {
          requestQueue.delete(rid);
          if (showLoading) wx.hideLoading();
          if (attempt < retries && err.errMsg && err.errMsg.indexOf('timeout') !== -1) {
            console.warn(`请求超时，第${attempt + 1}次重试:`, url);
            wx.hideLoading();
            return new Promise(res => setTimeout(res, retryDelay))
              .then(() => makeRequest(attempt + 1))
              .then(resolve)
              .catch(reject);
          } else {
            // 静默失败，不弹 toast（避免后台轮询/统计请求频繁弹窗）
            console.debug('网络请求失败:', url, err && err.errMsg);
            reject(err);
          }
        },
      });
      requestQueue.set(rid, requestTask);
    });
  };

  return makeRequest(0);
}

function uploadFile(filePath, url, formData = {}, fileFieldName = 'file') {
  wx.showLoading({ title: '上传中...', mask: true });
  let loadingShown = true;

  const authModule = getAuth();
  const authHeader = authModule.getAuthHeader();

  return new Promise((resolve, reject) => {
    let finished = false;
    const uploadTask = wx.uploadFile({
      url: BASE_URL + url,
      filePath,
      name: fileFieldName,
      formData,
      timeout: 120000,
      header: authHeader,
      success: (res) => {
        finished = true;
        if (loadingShown) { wx.hideLoading(); loadingShown = false; }
        if (res.statusCode === 401) {
          authModule.clearAuth();
          wx.showToast({ title: '登录已失效，请重新登录', icon: 'none' });
          reject(Object.assign(new Error('登录已失效'), { statusCode: 401 }));
        } else if (res.statusCode === 403) {
          wx.showToast({ title: '没有执行此操作的权限', icon: 'none' });
          reject(Object.assign(new Error('没有权限'), { statusCode: 403 }));
        } else if (res.statusCode === 200) {
          try {
            // wx.uploadFile 在 Windows 下可能按 GBK 解码 UTF-8 响应导致中文乱码
            // 尝试用 escape/decodeURIComponent 重新解码（Latin1→UTF-8 修复）
            let text = res.data;
            try {
              text = decodeURIComponent(escape(res.data));
            } catch (e) {
              // 重新解码失败，用原始字符串
              text = res.data;
            }
            const data = JSON.parse(text);
            if (data.code === 200) {
              resolve(data.data);
            } else {
              wx.showToast({ title: data.message || '上传失败', icon: 'none' });
              reject(new Error(data.message));
            }
          } catch (e) {
            console.error('上传响应解析失败:', res.data && res.data.substring(0, 200));
            reject(new Error('解析响应失败'));
          }
        } else {
          wx.showToast({ title: '上传失败: ' + res.statusCode, icon: 'none' });
          reject(new Error('上传失败: ' + res.statusCode));
        }
      },
      fail: (err) => {
        finished = true;
        if (loadingShown) { wx.hideLoading(); loadingShown = false; }
        wx.showToast({ title: '上传失败', icon: 'none' });
        reject(err);
      },
    });

    uploadTask.onProgressUpdate((res) => {
      if (!finished && loadingShown) {
        wx.showLoading({ title: '上传中 ' + res.progress + '%', mask: true });
      }
    });
  });
}

// 检测相关 API
const detectionAPI = {
  detectAudio(filePath) {
    return uploadFile(filePath, '/api/detection/audio');
  },
  detectAudioBatch(filePaths) {
    if (!Array.isArray(filePaths) || filePaths.length === 0) {
      return Promise.reject(new Error('没有可上传的音频文件'));
    }
    return Promise.all(filePaths.map((filePath) => this.detectAudio(filePath)));
  },
  detectVideo(filePath) {
    return uploadFile(filePath, '/api/detection/video');
  },
  detectText(text) {
    return request({
      url: '/api/detection/text',
      method: 'POST',
      data: { text, conversationId: getTextConversationId() },
      timeout: 120000,
      retries: 0,
      loadingTitle: 'AI分析中...',
    });
  },
  detectTextDocument(filePath) {
    return uploadFile(filePath, '/api/detection/text/document');
  },
  async detectMulti(audioPath, videoPath, text) {
    const staged = await uploadFile(audioPath, '/api/detection/multi/audio-stage');
    if (!staged || !staged.audioToken) {
      throw new Error('音频暂存未返回有效凭证');
    }
    return uploadFile(videoPath, '/api/detection/multi', {
      audioToken: staged.audioToken,
      text: text || '',
    }, 'video');
  },
};

// 检测记录 API
const recordAPI = {
  save(record) {
    return request({ url: '/api/records/save', method: 'POST', data: record, showLoading: false });
  },
  list(userId, limit) {
    let url = '/api/records/list';
    const params = [];
    if (userId) params.push('userId=' + userId);
    if (limit) params.push('limit=' + limit);
    if (params.length) url += '?' + params.join('&');
    return request({ url, showLoading: false, timeout: 5000, retries: 0 });
  },
};

// 模拟诈骗 API
const simulateAPI = {
  getScripts() {
    return request({ url: '/api/simulate/scripts', method: 'GET', showLoading: false });
  },
  startSimulation(scriptId) {
    return request({ url: '/api/simulate/start/' + scriptId, method: 'GET', showLoading: false });
  },
  continueConversation(scriptId, userInput, history) {
    return request({ url: '/api/simulate/chat', method: 'POST', data: { scriptId, message: userInput, history }, showLoading: false });
  },
  endSimulation(scriptId, messages) {
    return request({ url: '/api/simulate/end', method: 'POST', data: { scriptId, messages }, showLoading: false });
  },
};

// RAG API
const ragAPI = {
  query(query) {
    return request({ url: '/api/rag/query', method: 'GET', data: { q: query }, showLoading: false });
  },
  analyze(text) {
    return request({ url: '/api/llm/analyze', method: 'POST', data: { text }, showLoading: false, timeout: 60000, retries: 0 });
  },
  search(keyword) {
    return request({ url: '/api/knowledge/search', method: 'GET', data: { keyword }, showLoading: false });
  },
};

/**
 * 流式请求 (SSE)
 */
function requestStream(url, data, callbacks) {
  const { onMessage, onDone, onError } = callbacks;
  const authModule = getAuth();
  let buffer = '';
  let fullContent = '';
  let timedOut = false;
  let completed = false;

  const requestTask = wx.request({
    url: BASE_URL + url,
    method: 'POST',
    data,
    timeout: 60000,
    header: {
      'Content-Type': 'application/json',
      ...authModule.getAuthHeader(),
    },
    enableChunked: true,
    success: (res) => {
      if (completed) return;
      clearTimeout(timeoutTimer);
      completed = true;
      if (res.statusCode === 200) {
        if (onDone) onDone(fullContent);
      } else if (onError) {
        onError(new Error('SSE请求失败: ' + res.statusCode));
      }
    },
    fail: (err) => {
      if (completed) return;
      clearTimeout(timeoutTimer);
      completed = true;
      if (onError) onError(err);
    },
  });

  const timeoutTimer = setTimeout(() => {
    if (completed) return;
    timedOut = true;
    completed = true;
    requestTask.abort();
    if (onError) onError(new Error('SSE请求超时'));
  }, 60000);

  requestTask.onChunkReceived((res) => {
    if (timedOut || completed) return;
    try {
      const rawBytes = res.data;
      const text = typeof TextDecoder !== 'undefined'
        ? new TextDecoder('utf-8').decode(rawBytes)
        : String.fromCharCode.apply(null, new Uint8Array(rawBytes));
      buffer += text;
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
        const lines = part.split('\n');
        let eventName = 'message';
        const dataLines = [];
        for (const line of lines) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim();
          if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }
        if (dataLines.length === 0) continue;
        const payload = dataLines.join('\n');
        if (eventName === 'done' || payload === '[DONE]') {
          clearTimeout(timeoutTimer);
          completed = true;
          if (onDone) onDone(fullContent || payload);
          return;
        }
        if (eventName === 'error') {
          clearTimeout(timeoutTimer);
          completed = true;
          if (onError) onError(new Error(payload || 'SSE请求失败'));
          return;
        }
        try {
          const parsed = JSON.parse(payload);
          if (parsed.content) {
            fullContent += parsed.content;
            if (onMessage) onMessage(parsed.content, fullContent);
          }
        } catch (_) {
          fullContent += payload;
          if (onMessage) onMessage(payload, fullContent);
        }
      }
    } catch (err) { console.warn('[SSE] parse error:', err); }
  });

  return {
    abort: function() {
      clearTimeout(timeoutTimer);
      requestTask.abort();
    },
    requestTask: requestTask,
  };
}

function abortAllRequests() {
  for (const [rid, task] of requestQueue) {
    try { task.abort(); } catch (e) { /* ignore */ }
  }
  requestQueue.clear();
}

module.exports = {
  request,
  uploadFile,
  requestStream,
  abortAllRequests,
  detectionAPI,
  recordAPI,
  simulateAPI,
  ragAPI,
  BASE_URL,
};
