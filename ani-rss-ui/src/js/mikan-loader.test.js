import {describe, expect, it} from 'vitest'
import {
  enrichScores,
  extractMikanId,
  mergeScores,
  normalizeEnrichmentResponse
} from './mikan-loader.js'

describe('mikan-loader', () => {
  it('extracts only trusted numeric Mikan ids', () => {
    expect(extractMikanId('https://mikan.example/Home/Bangumi/123?x=1')).toBe('123')
    expect(extractMikanId('https://mikan.example/Home/Bangumi/not-number')).toBe('')
    expect(extractMikanId('https://mikan.example/Home/Other/123')).toBe('')
  })

  it('does not turn a missing score response into an unsubscribed item', () => {
    const weeks = [{items: [
      {url: 'https://mikan.example/Home/Bangumi/1', score: 8.1, exists: true},
      {url: 'https://mikan.example/Home/Bangumi/2', score: 0, exists: false}
    ]}]
    mergeScores(weeks, {
      2: {mikanId: '2', bgmId: '22', score: 9.2}
    }, ['22'])
    expect(weeks[0].items[0]).toMatchObject({score: 8.1, exists: true})
    expect(weeks[0].items[1]).toMatchObject({score: 9.2, bgmId: '22', exists: true})
  })

  it('preserves valid zero scores and never replaces fields with null or empty values', () => {
    const weeks = [{items: [{
      url: 'https://mikan.example/Home/Bangumi/1',
      score: 8.1,
      bgmId: '11',
      exists: true
    }]}]
    mergeScores(weeks, {
      1: {mikanId: '1', score: 0, bgmId: null}
    }, [])
    expect(weeks[0].items[0]).toMatchObject({score: 0, bgmId: '11', exists: true})
  })

  it('does not convert empty scalar scores into zero', () => {
    const weeks = [{items: [{
      url: 'https://mikan.example/Home/Bangumi/1',
      score: 8.1
    }]}]
    mergeScores(weeks, {1: ''})
    expect(weeks[0].items[0].score).toBe(8.1)
  })

  it('normalizes direct and API-enveloped responses without accepting outside ids', () => {
    expect(normalizeEnrichmentResponse({
      data: {scores: {1: {score: 0}, 99: {score: 9}}, retryableMikanIds: ['1', '99']}
    }, {
      batch: ['1', '2'], resultKey: 'scores', retryKey: 'retryableMikanIds'
    })).toMatchObject({
      valid: true,
      values: {1: {score: 0}},
      retryable: ['1']
    })
    expect(normalizeEnrichmentResponse({scores: {}, retryableSubjectIds: []}, {
      batch: ['1'], resultKey: 'subjects', retryKey: 'retryableSubjectIds'
    }).valid).toBe(false)
  })

  it('retries only retryable ids after the list has already rendered', async () => {
    const calls = []
    let attempt = 0
    const pending = await enrichScores({
      items: [
        {url: 'https://mikan.example/Home/Bangumi/1'},
        {url: 'https://mikan.example/Home/Bangumi/2'}
      ],
      fetchScores: async ids => {
        calls.push(ids)
        attempt++
        return attempt === 1
          ? {data: {scores: {}, retryableMikanIds: ['1', '2']}}
          : {data: {scores: {1: {mikanId: '1', score: 7.5}}, retryableMikanIds: []}}
      },
      sleep: async () => {}
    })
    expect(calls).toEqual([['1', '2'], ['1', '2']])
    expect(pending).toEqual([])
  })

  it('renders partial results immediately and retries the same id when it remains retryable', async () => {
    const updates = []
    let attempt = 0
    const pending = await enrichScores({
      items: [
        {url: 'https://mikan.example/Home/Bangumi/1'},
        {url: 'https://mikan.example/Home/Bangumi/2'}
      ],
      fetchScores: async ids => {
        attempt++
        return attempt === 1
          ? {data: {
            scores: {1: {mikanId: '1', score: 0}},
            retryableMikanIds: ['1', '2']
          }}
          : {scores: {
            1: {mikanId: '1', score: 7},
            2: {mikanId: '2', score: 8}
          }, retryableMikanIds: []}
      },
      onUpdate: payload => updates.push(payload),
      sleep: async () => {}
    })
    expect(updates).toHaveLength(2)
    expect(updates[0].scores[1].score).toBe(0)
    expect(pending).toEqual([])
  })

  it('keeps a malformed response retryable instead of treating it as an empty success', async () => {
    let attempt = 0
    const pending = await enrichScores({
      items: [{url: 'https://mikan.example/Home/Bangumi/1'}],
      fetchScores: async () => {
        attempt++
        return attempt === 1
          ? {data: {scores: {}}}
          : {data: {scores: {1: {score: 8}}, retryableMikanIds: []}}
      },
      sleep: async () => {}
    })
    expect(attempt).toBe(2)
    expect(pending).toEqual([])
  })

  it('gives every 48-id batch an initial attempt before retrying a slow batch', async () => {
    const calls = []
    let time = 0
    const pending = await enrichScores({
      items: Array.from({length: 96}, (_, index) => ({
        url: `https://mikan.example/Home/Bangumi/${index + 1}`
      })),
      fetchScores: async ids => {
        calls.push(ids)
        return {data: {scores: {}, retryableMikanIds: ids}}
      },
      maxDuration: 1000,
      now: () => time,
      sleep: async milliseconds => { time += milliseconds }
    })
    expect(calls.length).toBeGreaterThanOrEqual(2)
    expect(calls[0]).toHaveLength(48)
    expect(calls[1]).toHaveLength(48)
    expect(pending).toHaveLength(96)
  })

  it('returns at the deadline even when an upstream promise ignores abort', async () => {
    let release
    const hanging = new Promise(resolve => { release = resolve })
    let settled = false
    const work = enrichScores({
      items: [{url: 'https://mikan.example/Home/Bangumi/1'}],
      fetchScores: () => hanging,
      maxDuration: 20
    }).finally(() => { settled = true })
    await new Promise(resolve => setTimeout(resolve, 60))
    expect(settled).toBe(true)
    release({data: {scores: {}, retryableMikanIds: []}})
    await work
  })

  it('starts later batches before an earlier hanging batch reaches the deadline', async () => {
    const calls = []
    let releaseFirst
    const firstBatch = new Promise(resolve => { releaseFirst = resolve })
    const work = enrichScores({
      items: Array.from({length: 96}, (_, index) => ({
        url: `https://mikan.example/Home/Bangumi/${index + 1}`
      })),
      fetchScores: async ids => {
        calls.push(ids)
        if (calls.length === 1) return firstBatch
        return {
          data: {
            scores: Object.fromEntries(ids.map(id => [id, {mikanId: id, score: 7}])),
            retryableMikanIds: []
          }
        }
      },
      maxDuration: 30
    })

    const pending = await work
    expect(calls.length).toBeGreaterThanOrEqual(2)
    expect(calls[0]).toHaveLength(48)
    expect(calls[1]).toHaveLength(48)
    expect(pending.length).toBeLessThanOrEqual(48)
    releaseFirst({data: {scores: {}, retryableMikanIds: []}})
  })

  it('never starts more than two batch requests at once', async () => {
    let active = 0
    let maximum = 0
    const pending = await enrichScores({
      items: Array.from({length: 144}, (_, index) => ({
        url: `https://mikan.example/Home/Bangumi/${index + 1}`
      })),
      fetchScores: async ids => {
        active++
        maximum = Math.max(maximum, active)
        await new Promise(resolve => setTimeout(resolve, 15))
        active--
        return {data: {
          scores: Object.fromEntries(ids.map(id => [id, {mikanId: id, score: 8}])),
          retryableMikanIds: []
        }}
      },
      maxDuration: 1000
    })
    expect(maximum).toBe(2)
    expect(pending).toEqual([])
  })
})
