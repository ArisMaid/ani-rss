import {describe, expect, it} from 'vitest'
import {enrichScores, extractMikanId, mergeScores} from './mikan-loader.js'

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
})
