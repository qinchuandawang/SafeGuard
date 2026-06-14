<template>
  <router-view />
</template>

<style>
html,
body,
#app {
  width: 100%;
  min-height: 100%;
  margin: 0;
}

body {
  font-family: Inter, "SF Pro Display", "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  color: #0f172a;
  background: #eef8ff;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

* {
  box-sizing: border-box;
}

::selection {
  color: #ffffff;
  background: #0ea5e9;
}
</style>
