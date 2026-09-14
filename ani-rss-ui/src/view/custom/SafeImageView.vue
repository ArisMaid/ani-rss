<template>
  <span class="safe-image-shell" :class="attrs.class" :style="shellStyle">
    <img
        :key="imageKey"
        ref="lazyTarget"
        v-bind="attrs"
        :src="imageSrc || undefined"
        :loading="lazy ? 'lazy' : 'eager'"
        decoding="async"
        :aria-busy="loadState === 'loading' ? 'true' : undefined"
        @load="handleLoad"
        @error="handleError"
    />
    <span v-if="loadState === 'error' && active" class="safe-image-placeholder" role="status">
      <span>图片暂未加载</span>
      <button type="button" @click.stop="retry">重试</button>
    </span>
  </span>
</template>

<script setup>
import {computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, useAttrs, watch} from 'vue'
import * as http from '@/js/http.js'

defineOptions({inheritAttrs: false})

const props = defineProps({
  srcUrl: {type: String, default: ''},
  lazy: {type: Boolean, default: false},
  active: {type: Boolean, default: true}
})

const attrs = useAttrs()
const active = computed(() => props.active)
const shellStyle = computed(() => {
  const source = attrs.style
  if (typeof source === 'string') return source
  const style = {...(source || {})}
  if (style.width == null && attrs.width != null) style.width = dimension(attrs.width)
  if (style.height == null && attrs.height != null) style.height = dimension(attrs.height)
  return style
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

const dimension = value => typeof value === 'number' ? `${value}px` : String(value)
const resolved = ref('')
const imageSrc = ref('')
const lazyTarget = ref()
const loadState = ref('idle')
const loaded = ref(false)
const readyToResolve = ref(!props.lazy)
const imageKey = ref(0)
let generation = 0
let observer
let requestController

const disconnectObserver = () => {
  observer?.disconnect()
  observer = undefined
}

const cancelPendingRequest = () => {
  requestController?.abort()
  requestController = undefined
}

const syncImageSource = () => {
  if (!props.active || !readyToResolve.value || !resolved.value) {
    if (!loaded.value) imageSrc.value = ''
    return
  }
  imageSrc.value = resolved.value
  loadState.value = loaded.value ? 'loaded' : 'loading'
}

const resolveCurrentSource = async currentGeneration => {
  const value = props.srcUrl
  if (!props.active || !readyToResolve.value || !value
      || currentGeneration !== generation) return

  if (resolved.value) {
    syncImageSource()
    return
  }

  loadState.value = 'loading'
  const publicUrl = publicImageUrl(value)
  if (publicUrl) {
    if (currentGeneration !== generation || !props.active) return
    resolved.value = publicUrl
    syncImageSource()
    return
  }

  const controller = new AbortController()
  requestController = controller
  try {
    const response = await http.cacheImage(value, {signal: controller.signal})
    if (currentGeneration !== generation || !props.active || controller.signal.aborted) return
    resolved.value = new URL(
        `api/v2/images/${encodeURIComponent(response.data.id)}`,
        document.baseURI
    ).toString()
    syncImageSource()
  } catch (error) {
    if (currentGeneration !== generation || error?.code === 'REQUEST_ABORTED') return
    loadState.value = 'error'
    imageSrc.value = ''
  } finally {
    if (requestController === controller) requestController = undefined
  }
}

const startCurrentSource = () => {
  const currentGeneration = ++generation
  void resolveCurrentSource(currentGeneration)
}

const observeLazyTarget = () => {
  if (!props.active || !props.lazy || readyToResolve.value || loaded.value) return
  if (!lazyTarget.value) return
  if (typeof IntersectionObserver === 'undefined') {
    readyToResolve.value = true
    startCurrentSource()
    return
  }
  disconnectObserver()
  observer = new IntersectionObserver(entries => {
    if (!entries.some(entry => entry.isIntersecting)) return
    disconnectObserver()
    readyToResolve.value = true
    startCurrentSource()
  })
  observer.observe(lazyTarget.value)
}

const scheduleLazyObservation = () => {
  void nextTick(observeLazyTarget)
}

const pauseForLifecycle = () => {
  generation++
  cancelPendingRequest()
  disconnectObserver()
  if (!loaded.value) {
    resolved.value = ''
    imageSrc.value = ''
    loadState.value = 'idle'
    readyToResolve.value = !props.lazy
  }
}

const resumeFromLifecycle = () => {
  if (!props.active || !props.srcUrl) return
  if (loaded.value) {
    imageSrc.value = resolved.value
    loadState.value = 'loaded'
    return
  }
  if (props.lazy) {
    readyToResolve.value = false
    scheduleLazyObservation()
    return
  }
  readyToResolve.value = true
  startCurrentSource()
}

const handleLoad = () => {
  if (!props.active || !imageSrc.value) return
  loaded.value = true
  loadState.value = 'loaded'
}

const handleError = () => {
  if (!props.active || !imageSrc.value) return
  loaded.value = false
  imageSrc.value = ''
  loadState.value = 'error'
}

const retry = () => {
  if (!props.active || !props.srcUrl) return
  generation++
  cancelPendingRequest()
  disconnectObserver()
  resolved.value = ''
  imageSrc.value = ''
  loaded.value = false
  loadState.value = 'idle'
  readyToResolve.value = true
  imageKey.value++
  startCurrentSource()
}

watch(() => props.srcUrl, () => {
  generation++
  cancelPendingRequest()
  disconnectObserver()
  resolved.value = ''
  imageSrc.value = ''
  loaded.value = false
  loadState.value = 'idle'
  readyToResolve.value = !props.lazy
  if (!props.active || !props.srcUrl) return
  if (props.lazy) scheduleLazyObservation()
  else startCurrentSource()
}, {immediate: true})

watch(() => props.active, value => {
  if (value) resumeFromLifecycle()
  else pauseForLifecycle()
})

onMounted(() => {
  if (props.active && props.lazy) scheduleLazyObservation()
})

onActivated(resumeFromLifecycle)
onDeactivated(pauseForLifecycle)

onBeforeUnmount(() => {
  generation++
  cancelPendingRequest()
  disconnectObserver()
})
</script>

<style scoped>
.safe-image-shell {
  position: relative;
  display: inline-block;
  max-width: 100%;
  vertical-align: middle;
}

.safe-image-shell > img {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
  cursor: inherit;
}

.safe-image-placeholder {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px;
  box-sizing: border-box;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.2;
  text-align: center;
  background: color-mix(in srgb, var(--el-fill-color-light) 88%, transparent);
}

.safe-image-placeholder button {
  padding: 0;
  border: 0;
  color: var(--el-color-primary);
  font-size: inherit;
  line-height: inherit;
  cursor: pointer;
  background: transparent;
}
</style>
