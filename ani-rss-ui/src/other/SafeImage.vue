<template>
  <img v-if="resolved" v-bind="$attrs" :src="resolved"/>
</template>

<script setup>
import {ref, watch} from 'vue'
import * as http from '@/js/http.js'

defineOptions({inheritAttrs: false})
const props = defineProps({srcUrl: {type: String, default: ''}})
const resolved = ref('')
let generation = 0

watch(() => props.srcUrl, async (value) => {
  const current = ++generation
  resolved.value = ''
  if (!value) return
  try {
    const response = await http.cacheImage(value)
    if (current !== generation) return
    resolved.value = new URL(`api/v2/images/${encodeURIComponent(response.data.id)}`, document.baseURI).toString()
  } catch (e) {
    if (current === generation) resolved.value = ''
  }
}, {immediate: true})
</script>
