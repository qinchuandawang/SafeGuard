// pages/about/about.js - 关于我们逻辑

const app = getApp();

Page({
  data: {},

  onLoad() {},

  // 返回
  onBack() {
    wx.navigateBack({
      fail: () => {
        wx.switchTab({ url: '/pages/index/index' });
      },
    });
  },

  // 回到顶部
  backToTop() {
    wx.pageScrollTo({
      scrollTop: 0,
      duration: 300,
    });
  },

  onShareAppMessage() {
    return {
      title: '诈骗克星 - 关于我们',
      path: '/pages/about/about',
    };
  },

  onShareTimeline() {
    return {
      title: '诈骗克星 - AI反诈骗检测系统',
    };
  },
});
