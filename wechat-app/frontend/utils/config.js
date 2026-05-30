// utils/config.js - 应用配置
//
// ⚠️ 真机测试必读：
// 手机连接电脑的网络请求时，不能使用 localhost，
// 需要将以下 API_BASE_URL 改为电脑的局域网 IP 地址。
//
// 例如: http://192.168.1.100:8080
//
// 获取电脑局域网 IP 的方法：
//   Windows: 打开 cmd 输入 ipconfig，找到 IPv4 地址
//   Mac: 系统设置 → 网络 → 查看 IP
//   Linux: 终端输入 ip addr | grep inet
//
// 📝 生产环境部署建议：
//   - 将 API_BASE_URL 替换为线上服务器域名（需在微信公众平台配置白名单）
//   - 域名必须为 https（微信小程序生产环境限制）

const CONFIG = {
  // ==========================================
  // API 基础地址
  //   开发环境: http://localhost:8080
  //   真机测试: http://192.168.x.x:8080（改为你的电脑实际IP）
  // ==========================================
  API_BASE_URL: 'http://localhost:8080',

  // ==========================================
  // 开发模式
  //   生产环境请设为 false
  // ==========================================
  DEV_MODE: false,
};

// 环境兼容检测（P2 fix: E10）
try {
  if (typeof wx !== 'undefined' && wx.canIUse('getDeviceInfo')) {
    const deviceInfo = wx.getDeviceInfo();
    // 真机环境下使用 localhost 会无法连接，给出提示
    if (deviceInfo.platform !== 'devtools' && CONFIG.API_BASE_URL.indexOf('localhost') !== -1) {
      console.warn(
        '[SafeGuard Config] ⚠️ 检测到真机环境但 API_BASE_URL 仍为 localhost，' +
        '请修改 config.js 中的 API_BASE_URL 为电脑局域网 IP 地址。'
      );
    }
  }
} catch (e) {
  // 初始化阶段可能 wx 尚未 ready，静默忽略
}

module.exports = CONFIG;
