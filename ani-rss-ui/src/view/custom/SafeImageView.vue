<template>
  <img v-if="resolved" v-bind="$attrs" :src="resolved"/>
  <img v-else-if="lazy" ref="lazyTarget" v-bind="$attrs" loading="lazy"/>
</template>

<script setup>
import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import * as http from '@/js/http.js'

defineOptions({inheritAttrs: false})

const props = defineProps({
  srcUrl: {type: String, default: ''},
  lazy: {type: Boolean, default: false}
})

const resolved = ref('')
const lazyTarget = ref()
const readyToResolve = ref(!props.lazy)
let generation = 0
let observer

watch([() => props.srcUrl, readyToResolve], async ([value, ready]) => {
  const current = ++generation
  resolved.value = ''
  if (!value || !ready) return
  try {
    const response = await http.cacheImage(value)
    if (current !== generation) return
    resolved.value = new URL(
        `api/v2/images/${encodeURIComponent(response.data.id)}`,
        document.baseURI
    ).toString()
  } catch {
    if (current === generation) resolved.value = ''
  }
}, {immediate: true})

onMounted(() => {
  if (!props.lazy) return
  if (typeof IntersectionObserver === 'undefined' || !lazyTarget.value) {
    readyToResolve.value = true
    return
  }
  observer = new IntersectionObserver(entries => {
    if (!entries.some(entry => entry.isIntersecting)) return
    readyToResolve.value = true
    observer?.disconnect()
    observer = undefined
  })
  observer.observe(lazyTarget.value)
})

onBeforeUnmount(() => {
  generation++
  observer?.disconnect()
  observer = undefined
})
</script>
