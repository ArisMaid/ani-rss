export const SCORE_BATCH_SIZE = 48
export const SCORE_RETRY_DELAYS = [500, 1000, 2000]

export const extractMikanId = value => {
  const match = String(value || '').match(/\/Home\/Bangumi\/(\d+)(?:[/?#]|$)/i)
  return match?.[1] || ''
}

export const uniqueMikanIds = items => Array.from(new Set(
    (items || [])
        .map(item => extractMikanId(item?.url))
        .filter(Boolean)
))

export const chunk = (items, size = SCORE_BATCH_SIZE) => {
  const result = []
  for (let index = 0; index < items.length; index += size) {
    result.push(items.slice(index, index + size))
  }
  return result
}

const wait = (milliseconds, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) {
    reject(Object.assign(new Error('请求已取消'), {name: 'AbortError'}))
    return
  }
  const timer = setTimeout(resolve, milliseconds)
  signal?.addEventListener('abort', () => {
    clearTimeout(timer)
    reject(Object.assign(new Error('请求已取消'), {name: 'AbortError'}))
  }, {once: true})
})

export const mergeScores = (weeks, scores, subscribedBgmIds = []) => {
  const subscribed = new Set((subscribedBgmIds || []).map(String))
  const scoreMap = scores || {}
  for (const week of weeks || []) {
    for (const item of week?.items || []) {
      const mikanId = extractMikanId(item?.url)
      const score = scoreMap[mikanId]
      if (!score) continue
      const value = Number(score.score)
      if (Number.isFinite(value)) item.score = value
      if (score.bgmId !== undefined && score.bgmId !== null) {
        item.bgmId = String(score.bgmId)
        if (subscribed.has(String(score.bgmId))) item.exists = true
      }
    }
  }
}

/**
 * Progressive, cancellable score enrichment used by the Mikan picker.
 * The list request remains independent: a slow score source only produces
 * retryable ids and never controls the list loading state.
 */
export const enrichScores = async ({
  items,
  fetchScores,
  onUpdate,
  signal,
  maxDuration = 20_000,
  sleep = wait,
  now = () => Date.now()
}) => {
  let pending = uniqueMikanIds(items)
  const deadline = now() + maxDuration

  while (pending.length && now() < deadline) {
    const currentBatch = pending.splice(0, SCORE_BATCH_SIZE)
    let unresolved = currentBatch
    let retryIndex = 0
    while (unresolved.length && now() < deadline) {
      let payload
      try {
        const response = await fetchScores(unresolved, {signal})
        payload = response?.data || response || {}
      } catch (error) {
        if (error?.name === 'AbortError' || signal?.aborted) throw error
        payload = {retryableMikanIds: unresolved}
      }

      const scores = payload.scores || {}
      const retryable = new Set((payload.retryableMikanIds || []).map(String))
      unresolved = unresolved.filter(id => retryable.has(String(id)) && !Object.hasOwn(scores, id))
      onUpdate?.({
        scores,
        subscribedBgmIds: payload.subscribedBgmIds || [],
        retryableMikanIds: unresolved
      })

      if (unresolved.length && now() < deadline) {
        const delay = SCORE_RETRY_DELAYS[Math.min(retryIndex, SCORE_RETRY_DELAYS.length - 1)] || 2000
        retryIndex++
        await sleep(delay, signal)
      }
    }
    pending = [...unresolved, ...pending]
  }

  return pending
}
