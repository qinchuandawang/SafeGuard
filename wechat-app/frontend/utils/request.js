// utils/request.js - 网络请求封装（支持认证令牌，无 mock）
// 注意：为避免与 auth.js 循环依赖，auth 使用惰性加载

const CONFIG = require('./config');

const BASE_URL = CONFIG && CONFIG.API_BASE_URL ? CONFIG.API_BASE_URL : '';

function getAuth() {
  return require('./auth');
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
    timeout = 15000,
    retries = 2,
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
            wx.showToast({ title: '登录已过期，请重新打开小程序', icon: 'none' });
            reject(new Error('登录已过期'));
            return;
          }
          if (res.statusCode === 429) {
            wx.showToast({ title: '请求过于频繁，请稍后再试', icon: 'none' });
            reject(new Error('请求频率限制'));
            return;
          }
          if (res.statusCode === 200) {
            const { code, message, data: resData } = res.data;
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

function uploadFile(filePath, url, formData = {}) {
  wx.showLoading({ title: '上传中...', mask: true });

  const authModule = getAuth();
  const authHeader = authModule.getAuthHeader();

  return new Promise((resolve, reject) => {
    // 读取文件为 ArrayBuffer，然后用 wx.request 手动构造 multipart 上传
    // 这样可以用 responseType: 'arraybuffer' + TextDecoder 手动 UTF-8 解码响应
    // 避免 wx.uploadFile 在 Windows 下按 GBK 解码 UTF-8 响应导致中文乱码
    const fs = wx.getFileSystemManager();
    fs.readFile({
      filePath,
      success: (fileRes) => {
        const fileName = (filePath.split(/[\\/]/).pop()) || 'file';
        const boundary = '----SafeGuard' + Date.now() + Math.random().toString(36).slice(2);

        // 构造 multipart/form-data 各部分
        const headerPart = '--' + boundary + '\r\n' +
          'Content-Disposition: form-data; name="file"; filename="' + fileName + '"\r\n' +
          'Content-Type: application/octet-stream\r\n\r\n';
        const footerPart = '\r\n--' + boundary + '--\r\n';

        // 用 TextEncoder 编码文本部分（UTF-8）
        const encoder = new TextEncoder();
        const headerBytes = encoder.encode(headerPart);
        const footerBytes = encoder.encode(footerPart);
        const fileBytes = new Uint8Array(fileRes.data);

        // 拼接完整的 multipart 请求体
        const body = new Uint8Array(headerBytes.length + fileBytes.length + footerBytes.length);
        body.set(headerBytes, 0);
        body.set(fileBytes, headerBytes.length);
        body.set(footerBytes, headerBytes.length + fileBytes.length);

        wx.request({
          url: BASE_URL + url,
          method: 'POST',
          data: body.buffer,
          timeout: 120000,
          responseType: 'arraybuffer',
          header: {
            'Content-Type': 'multipart/form-data; boundary=' + boundary,
            ...authHeader,
          },
          success: (res) => {
            wx.hideLoading();
            // 用 TextDecoder 手动 UTF-8 解码，避免 wx.uploadFile 的 GBK 乱码
            const decoder = new TextDecoder('utf-8');
            const text = decoder.decode(new Uint8Array(res.data));
            try {
              const data = JSON.parse(text);
              if (data.code === 200) {
                resolve(data.data);
              } else {
                wx.showToast({ title: data.message || '上传失败', icon: 'none' });
                reject(new Error(data.message));
              }
            } catch (e) {
              console.error('响应解析失败:', text.substring(0, 200));
              reject(new Error('解析响应失败'));
            }
          },
          fail: (err) => {
            wx.hideLoading();
            wx.showToast({ title: '上传失败', icon: 'none' });
            reject(err);
          },
        });
      },
      fail: (err) => {
        wx.hideLoading();
        wx.showToast({ title: '读取文件失败', icon: 'none' });
        reject(err);
      },
    });
  });
}

// 检测相关 API
const detectionAPI = {
  detectAudio(filePath) {
    return uploadFile(filePath, '/api/detection/audio');
  },
  detectVideo(filePath) {
    return uploadFile(filePath, '/api/detection/video');
  },
  detectText(text) {
    return request({ url: '/api/detection/text', method: 'POST', data: { text } });
  },
  detectMulti(audioPath, videoPath, text) {
    return request({
      url: '/api/detection/multi',
      method: 'POST',
      data: { text: text || '', audioResult: audioPath ? { path: audioPath } : null, videoResult: videoPath ? { path: videoPath } : null },
    });
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
    return request({ url, showLoading: false });
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
    return request({ url: '/api/detection/text', method: 'POST', data: { text }, showLoading: false });
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
  const requestTask = wx.request({
    url: BASE_URL + url,
    method: 'POST',
    data,
    timeout: 30000,
    header: {
      'Content-Type': 'application/json',
      ...authModule.getAuthHeader(),
    },
    enableChunked: true,
    success: () => {},
    fail: (err) => { if (onError) onError(err); },
  });

  let buffer = '';
  let fullContent = '';
  let timedOut = false;

  const timeoutTimer = setTimeout(() => {
    timedOut = true;
    requestTask.abort();
    if (onError) onError(new Error('SSE请求超时'));
  }, 30000);

  requestTask.onChunkReceived((res) => {
    if (timedOut) return;
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
        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const payload = line.slice(6).trim();
            if (payload === '[DONE]') {
              clearTimeout(timeoutTimer);
              if (onDone) onDone(fullContent);
              return;
            }
            try {
              const parsed = JSON.parse(payload);
              if (parsed.content) { fullContent += parsed.content; if (onMessage) onMessage(parsed.content, fullContent); }
            } catch (_) { fullContent += payload; if (onMessage) onMessage(payload, fullContent); }
          }
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
