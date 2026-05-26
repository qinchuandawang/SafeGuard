// utils/auth.js - 微信小程序登录与令牌管理
// 注意：为避免与 request.js 循环依赖，request 使用惰性加载

const CONFIG = require('./config');

function getRequest() {
  return require('./request');
}

const AUTH_KEY = 'safeguard_auth_token';
const USER_KEY = 'safeguard_user_info';
const TOKEN_EXPIRY_KEY = 'safeguard_token_expiry';
const LOGIN_MAX_RETRIES = 3;

function getToken() {
  try {
    return wx.getStorageSync(AUTH_KEY) || null;
  } catch (e) {
    return null;
  }
}

function getUserInfo() {
  try {
    return wx.getStorageSync(USER_KEY) || null;
  } catch (e) {
    return null;
  }
}

function saveAuth(token, userInfo) {
  try {
    wx.setStorageSync(AUTH_KEY, token);
    wx.setStorageSync(USER_KEY, userInfo);
    wx.setStorageSync(TOKEN_EXPIRY_KEY, Date.now() + 7200000);
  } catch (e) {
    console.error('保存登录信息失败:', e);
  }
}

function isTokenExpired() {
  try {
    const expiry = wx.getStorageSync(TOKEN_EXPIRY_KEY);
    if (!expiry) return true;
    return Date.now() > expiry;
  } catch (e) {
    return true;
  }
}

function clearAuth() {
  try {
    wx.removeStorageSync(AUTH_KEY);
    wx.removeStorageSync(USER_KEY);
  } catch (e) {
    console.error('清除登录信息失败:', e);
  }
}

function isLoggedIn() {
  if (!getToken()) return false;
  if (isTokenExpired()) {
    clearAuth();
    return false;
  }
  return true;
}

async function login(retryCount) {
  const currentAttempt = retryCount || 0;
  if (currentAttempt >= LOGIN_MAX_RETRIES) {
    console.warn('登录重试次数已达上限');
    return null;
  }
  try {
    const loginRes = await wx.login();
    if (!loginRes.code) {
      console.warn('微信登录失败，使用匿名模式');
      return null;
    }

    const userProfile = { nickName: '用户', avatarUrl: '' };

    const body = {
      code: loginRes.code,
      nickname: userProfile?.nickName || '用户',
      avatarUrl: userProfile?.avatarUrl || '',
    };

    const data = await getRequest().request({
      url: '/api/auth/login',
      method: 'POST',
      data: body,
      showLoading: false,
      retries: 0,
    });

    if (data && data.token) {
      const userInfo = {
        userId: data.userId,
        nickname: data.nickname,
        avatarUrl: data.avatarUrl,
        role: data.role,
      };
      saveAuth(data.token, userInfo);
      return data;
    }
    return null;
  } catch (err) {
    const isConnError = err && err.errMsg && (
      err.errMsg.indexOf('fail') !== -1 ||
      err.errMsg.indexOf('refused') !== -1 ||
      err.errMsg.indexOf('timeout') !== -1
    );

    if (isConnError) {
      if (currentAttempt === 0) {
        console.warn('登录失败：后端服务不可达，跳过重试');
      }
      return null;
    }

    console.error('登录失败:', err);
    if (currentAttempt < LOGIN_MAX_RETRIES - 1) {
      console.warn(`登录重试第${currentAttempt + 1}次`);
      return new Promise((resolve) => {
        setTimeout(() => resolve(login(currentAttempt + 1)), 1000);
      });
    }
    return null;
  }
}

function getAuthHeader() {
  const token = getToken();
  if (token) {
    return { 'Authorization': 'Bearer ' + token };
  }
  return {};
}

function ensureLoggedIn() {
  if (isLoggedIn()) {
    return Promise.resolve(getUserInfo());
  }
  return login().then(data => {
    if (data) return { userId: data.userId, nickname: data.nickname, avatarUrl: data.avatarUrl, role: data.role };
    return null;
  });
}

module.exports = {
  getToken,
  getUserInfo,
  saveAuth,
  clearAuth,
  isLoggedIn,
  login,
  getAuthHeader,
  ensureLoggedIn,
  isTokenExpired,
  AUTH_KEY,
  USER_KEY,
};
