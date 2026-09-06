<template>
  <img v-if="resolved" v-bind="$attrs" :src="resolved" :loading="lazy ? 'lazy' : undefined"
       decoding="async"/>
  <img v-else-if="lazy" ref="lazyTarget" v-bind="$attrs" loading="lazy" decoding="async"/>
</template>

<script setup>
import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import * as http from '@/js/http.js'

defineOptions({inheritAttrs: false})

const props = defineProps({
  srcUrl: {type: String, default: ''},
  lazy: {type: Boolean, default: false}
})

const publicImageUrl = source => {
  try {
    const parsed = new URL(source)
    if (!['http:', 'https:'].includes(parsed.protocol)
        || parsed.username || parsed.password || parsed.hash) return ''
    const sensitive = ['token', 'signature', 'sig', 'credential', 'secret', 'auth', 'expires']
    if ([...parsed.searchParams.keys()].some(key =>
        sensitive.some(part => key.toLowerCase().includes(part)))) return ''
    const endpoint = new URL('api/v2/images', document.baseURI)
    endpoint.searchParams.set('url', source)
    return endpoint.toString()
  } catch {
    return ''
  }
}

const resolved = ref('')
const lazyTarget = ref()
const readyToResolve = ref(!props.lazy)
let generation = 0
let observer

watch([() => props.srcUrl, readyToResolve], async ([value, ready]) => {
  const current = ++generation
  resolved.value = ''
  if (!value || !ready) return
  const publicUrl = publicImageUrl(value)
  if (publicUrl) {
    resolved.value = publicUrl
    return
  }
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
