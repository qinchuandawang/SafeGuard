<template>
  <div :ref="setRef" class="echart-box" :style="{ height: height + 'px' }" />
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, markRaw } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, default: () => ({}) },
  height: { type: Number, default: 240 },
})

let chart = null
const el = ref(null)

function setRef(node) { el.value = node }

function init() {
  if (!el.value) return
  chart?.dispose()
  chart = markRaw(echarts.init(el.value, null, { renderer: 'canvas' }))
  chart.setOption(props.option)
  chart.resize()
}

watch(() => props.option, () => { if (chart) chart.setOption(props.option) }, { deep: true })

onMounted(() => {
  init()
  const ro = new ResizeObserver(() => chart?.resize())
  if (el.value) ro.observe(el.value)
  onUnmounted(() => { ro.disconnect(); chart?.dispose() })
})
</script>

<style scoped>
.echart-box { width: 100%; }
</style>
