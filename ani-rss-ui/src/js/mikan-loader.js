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

const abortError = () => Object.assign(new Error('请求已取消'), {name: 'AbortError'})

const uniqueIds = values => Array.from(new Set((values || []).map(String).filter(Boolean)))

const requestUntilDeadline = async ({fetchBatch, batch, signal, deadline, now}) => {
  if (signal?.aborted) throw abortError()
  const remaining = deadline - now()
  if (remaining <= 0) return {timedOut: true}

  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, {once: true})
  let timer
  try {
    const timeout = new Promise(resolve => {
      timer = setTimeout(() => {
        controller.abort()
        resolve({timedOut: true})
      }, remaining)
    })
    const response = await Promise.race([
      Promise.resolve(fetchBatch(batch, {signal: controller.signal})),
      timeout
    ])
    return response?.timedOut ? response : {response}
  } catch (error) {
    if (signal?.aborted || error?.name === 'AbortError') {
      if (signal?.aborted) throw error
      return {timedOut: true}
    }
    return {error}
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

const sleepUntilDeadline = async ({sleep, milliseconds, signal, deadline, now}) => {
  const remaining = Math.min(milliseconds, Math.max(0, deadline - now()))
  if (remaining <= 0) return false
  try {
    await Promise.race([
      Promise.resolve(sleep(remaining, signal)),
      new Promise(resolve => setTimeout(resolve, remaining))
    ])
    return now() < deadline
  } catch (error) {
    if (error?.name === 'AbortError' || signal?.aborted) throw error
    return false
  }
}

/**
 * Round-robin bounded enrichment.  Every 48-id batch gets one attempt before
 * a retry round begins, so a permanently slow first batch cannot starve later
 * visible cards.  Each request is also raced against the activation deadline.
 */
export const enrichIds = async ({
  ids,
  fetchBatch,
  onUpdate,
  signal,
  maxDuration = 20_000,
  sleep = wait,
  now = () => Date.now(),
  retryKey = 'retryableMikanIds',
  resultKey = 'scores'
}) => {
  let pending = uniqueIds(ids)
  const deadline = now() + maxDuration
  let retryRound = 0

  while (pending.length && now() < deadline) {
    const nextPending = []
    const batches = chunk(pending, SCORE_BATCH_SIZE)
    const results = await Promise.all(batches.map(batch => {
      if (now() >= deadline) return Promise.resolve({timedOut: true})
      return requestUntilDeadline({fetchBatch, batch, signal, deadline, now})
    }))

    for (let index = 0; index < batches.length; index++) {
      const batch = batches[index]
      const result = results[index]
      if (result.timedOut) {
        nextPending.push(...batch)
        continue
      }

      const payload = result.error
        ? {[retryKey]: batch}
        : (result.response?.data || result.response || {})
      const values = payload[resultKey] || {}
      const retryable = new Set((payload[retryKey] || []).map(String))
      const unresolved = batch.filter(id => retryable.has(String(id)) && !Object.hasOwn(values, id))
      nextPending.push(...unresolved)
      onUpdate?.(payload)
    }

    pending = uniqueIds(nextPending)
    if (!pending.length || now() >= deadline) break
    const delay = SCORE_RETRY_DELAYS[Math.min(retryRound, SCORE_RETRY_DELAYS.length - 1)] || 2000
    retryRound++
    if (!await sleepUntilDeadline({sleep, milliseconds: delay, signal, deadline, now})) break
  }

  return pending
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
  return enrichIds({
    ids: uniqueMikanIds(items),
    fetchBatch: fetchScores,
    onUpdate,
    signal,
    maxDuration,
    sleep,
    now,
    retryKey: 'retryableMikanIds',
    resultKey: 'scores'
  })
}

export const enrichSubjects = ({ids, fetchSubjects, onUpdate, ...options}) => enrichIds({
  ids,
  fetchBatch: fetchSubjects,
  onUpdate,
  retryKey: 'retryableSubjectIds',
  resultKey: 'subjects',
  ...options
})
