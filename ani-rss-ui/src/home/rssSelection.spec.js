import {describe, expect, it} from 'vitest'
import {mikanSearchQuery, normalizeRssSelection, subgroupMatchRules} from './rssSelection.js'

describe('RSS provider selection normalization', () => {
  it('accepts the legacy Vue Ref payload and ignores malformed match JSON', () => {
    const selection = normalizeRssSelection({
      value: {
        subgroup: ' Example Fansub ',
        url: ' https://feed.example.test/rss ',
        bgmUrl: ' https://bgm.tv/subject/1 ',
        match: 'not-json'
      }
    })

    expect(selection).toEqual({
      subgroup: 'Example Fansub',
      url: 'https://feed.example.test/rss',
      bgmUrl: 'https://bgm.tv/subject/1',
      match: []
    })
    expect(subgroupMatchRules(selection)).toEqual([])
  })

  it('preserves valid match rules and never throws for an invalid saved RSS URL', () => {
    const selection = normalizeRssSelection({
      subgroup: 'Example Fansub',
      url: 'https://feed.example.test/rss',
      match: '["720p", "1080p"]'
    })

    expect(subgroupMatchRules(selection)).toEqual([
      '{{Example Fansub}}:720p',
      '{{Example Fansub}}:1080p'
    ])
    expect(mikanSearchQuery({title: 'Example', url: 'not a URL'})).toBe('Example')
    expect(mikanSearchQuery({title: 'Example', url: 'https://mikanani.me/RSS/Bangumi?bangumiId=12'}))
      .toBe('id: 12')
  })
})
