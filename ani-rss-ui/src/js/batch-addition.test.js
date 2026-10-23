import {describe, expect, it} from 'vitest'
import {batchRequests} from './batch-addition.js'

describe('batch source conversion', () => {
    it('groups one work with independent standby sources and namespaced match rules', () => {
        const sources = [{rss:'https://mikan.test/RSS/Bangumi?bangumiId=1&subgroupid=1',label:'A',match:['1080']},
            {rss:'https://mikan.test/RSS/Bangumi?bangumiId=1&subgroupid=2',label:'B',match:['HEVC']}]
        const result = batchRequests(sources, 'mikan')
        expect(result).toHaveLength(1)
        expect(result[0].standbyRssList).toEqual([{label:'B',url:sources[1].rss,offset:0}])
        expect(result[0].match).toEqual(['{{A}}:1080','{{B}}:HEVC'])
        expect(sources[0]).not.toHaveProperty('standbyRssList')
    })
    it('never merges works without an upstream id and deduplicates repeated feeds', () => {
        const result = batchRequests([{rss:'https://example.test/a'},{rss:'https://example.test/b'},{rss:'https://example.test/a'}], 'anime-garden')
        expect(result).toHaveLength(2)
        expect(result[0].standbyRssList).toEqual([])
        result[0].match.push('local change')
        expect(result[1].match).toEqual([])
    })
    it('accepts serialized selections and keeps Bangumi identity across source types', () => {
        const result = batchRequests([JSON.stringify({rss:'https://anibt.test/feed?bgmId=23',name:'Group',bgmId:23})], 'ani-bt')
        expect(result[0]).toMatchObject({bgmUrl:'https://bgm.tv/subject/23',subgroup:'Group',type:'ani-bt'})
        expect(() => batchRequests([{rss:'javascript:alert(1)'}], 'mikan')).toThrow()
    })
})
