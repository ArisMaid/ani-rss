export const SCORE_BATCH_SIZE = 48
export const SCORE_RETRY_DELAYS = [500, 1000, 2000]
export const SCORE_MAX_CONCURRENT_BATCHES = 2
export const SCORE_MAX_ATTEMPT_DURATION = 5000

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
  let timer
  const cleanup = () => {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }
  const onAbort = () => {
    cleanup()
    reject(Object.assign(new Error('请求已取消'), {name: 'AbortError'}))
  }
  if (signal?.aborted) {
    onAbort()
    return
  }
  timer = setTimeout(() => {
    cleanup()
    resolve()
  }, milliseconds)
  signal?.addEventListener('abort', onAbort, {once: true})
})

export const mergeScores = (weeks, scores, subscribedBgmIds = []) => {
  const subscribed = new Set((subscribedBgmIds || []).map(String))
  const scoreMap = scores || {}
  for (const week of weeks || []) {
    for (const item of week?.items || []) {
      const mikanId = extractMikanId(item?.url)
      const score = scoreMap[mikanId]
      if (score === undefined || score === null) continue
      const rawScore = typeof score === 'object' ? score.score : score
      const hasUsableRawScore = (typeof rawScore === 'number' && !Number.isNaN(rawScore))
        || (typeof rawScore === 'string' && rawScore.trim() !== '')
      const value = hasUsableRawScore ? Number(rawScore) : Number.NaN
      if (Number.isFinite(value)) {
        item.score = value
      }
      if (typeof score !== 'object') continue
      if (score.bgmId !== undefined && score.bgmId !== null && String(score.bgmId).trim()) {
        item.bgmId = String(score.bgmId)
        if (subscribed.has(String(score.bgmId))) item.exists = true
      }
    }
  }
}

const abortError = () => Object.assign(new Error('请求已取消'), {name: 'AbortError'})

const uniqueIds = values => Array.from(new Set(
  (values || [])
    .filter(value => value !== null && value !== undefined)
    .map(value => String(value).trim())
    .filter(Boolean)
))

const requestUntilDeadline = async ({fetchBatch, batch, signal, deadline, now}) => {
  if (signal?.aborted) throw abortError()
  const remaining = deadline - now()
  if (remaining <= 0) return {timedOut: true}

  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, {once: true})
  let rejectOnAbort
  const cancelled = signal ? new Promise((_, reject) => {
    rejectOnAbort = () => reject(abortError())
    signal.addEventListener('abort', rejectOnAbort, {once: true})
    if (signal.aborted) rejectOnAbort()
  }) : null
  let timer
  try {
    const timeout = new Promise(resolve => {
      timer = setTimeout(() => {
        controller.abort()
        resolve({timedOut: true})
      }, Math.min(SCORE_MAX_ATTEMPT_DURATION, remaining))
    })
    const races = [
      Promise.resolve(fetchBatch(batch, {signal: controller.signal})),
      timeout
    ]
    if (cancelled) races.push(cancelled)
    const response = await Promise.race(races)
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
    if (signal && rejectOnAbort) signal.removeEventListener('abort', rejectOnAbort)
  }
}

const sleepUntilDeadline = async ({sleep, milliseconds, signal, deadline, now}) => {
  const remaining = Math.min(milliseconds, Math.max(0, deadline - now()))
  if (remaining <= 0) return false
  let fallbackTimer
  let rejectOnAbort
  const cancelled = signal ? new Promise((_, reject) => {
    rejectOnAbort = () => reject(abortError())
    signal.addEventListener('abort', rejectOnAbort, {once: true})
    if (signal.aborted) rejectOnAbort()
  }) : null
  try {
    const races = [
      Promise.resolve(sleep(remaining, signal)),
      new Promise(resolve => {
        fallbackTimer = setTimeout(resolve, remaining)
      })
    ]
    if (cancelled) races.push(cancelled)
    await Promise.race(races)
    return now() < deadline
  } catch (error) {
    if (error?.name === 'AbortError' || signal?.aborted) throw error
    return false
  } finally {
    clearTimeout(fallbackTimer)
    if (signal && rejectOnAbort) signal.removeEventListener('abort', rejectOnAbort)
  }
}

const isRecord = value => value !== null && typeof value === 'object'
  && !Array.isArray(value)

/**
 * Accepts both the current API envelope ({data: {...}}) and the original
 * direct payload ({...}), while keeping malformed responses retryable. The
 * returned maps are limited to the requested batch so a broken upstream
 * cannot update or schedule unrelated cards.
 */
export const normalizeEnrichmentResponse = (response, {
  batch,
  resultKey,
  retryKey
}) => {
  const requested = new Set(uniqueIds(batch))
  const envelope = isRecord(response) && isRecord(response.data)
    ? response.data
    : response
  if (!isRecord(envelope)
      || !Object.hasOwn(envelope, resultKey)
      || !Object.hasOwn(envelope, retryKey)
      || !isRecord(envelope[resultKey])
      || !Array.isArray(envelope[retryKey])) {
    return {
      valid: false,
      values: {},
      retryable: uniqueIds(batch),
      payload: null
    }
  }

  const values = Object.fromEntries(Object.entries(envelope[resultKey])
    .filter(([id]) => requested.has(String(id))))
  const retryable = uniqueIds(envelope[retryKey])
    .filter(id => requested.has(id))
  const payload = {
    ...envelope,
    [resultKey]: values,
    [retryKey]: retryable
  }
  return {valid: true, values, retryable, payload}
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
    let nextBatch = 0
    const worker = async () => {
      while (nextBatch < batches.length) {
        const batch = batches[nextBatch++]
        if (now() >= deadline) {
          nextPending.push(...batch)
          continue
        }
        const result = await requestUntilDeadline({fetchBatch, batch, signal, deadline, now})
        if (result.timedOut || result.error) {
          nextPending.push(...batch)
          continue
        }
        const normalized = normalizeEnrichmentResponse(result.response, {
          batch,
          resultKey,
          retryKey
        })
        if (!normalized.valid) {
          nextPending.push(...batch)
          continue
        }
        // A retryable id may also have partial fields in this response. It
        // must be rendered now and sent again in the next bounded round.
        onUpdate?.(normalized.payload)
        nextPending.push(...batch.filter(id => normalized.retryable.includes(id)))
      }
    }
    const workers = Array.from({
      length: Math.min(SCORE_MAX_CONCURRENT_BATCHES, batches.length)
    }, worker)
    await Promise.all(workers)

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
