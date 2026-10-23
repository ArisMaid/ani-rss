import {describe, expect, it} from 'vitest'
import {defaultPolicy, mappingText, moveDimension, parseMappings, quarterDateRange, mikanSeasonChoices} from './upgrade.js'
describe('upgrade settings and rule controls', () => {
    it('derives whole quarter dates including leap years without local timezone drift', () => {
        expect(quarterDateRange(2024, '冬')).toEqual({startDate: '2024-01-01', endDate: '2024-03-31'})
        expect(quarterDateRange(2024, '春')).toEqual({startDate: '2024-04-01', endDate: '2024-06-30'})
        expect(quarterDateRange(2026, '秋')).toEqual({startDate: '2026-10-01', endDate: '2026-12-31'})
        expect(quarterDateRange(null, '冬')).toBeNull()
        expect(quarterDateRange(2026, 'Q4')).toBeNull()
    })
    it('offers only quarters present in the upstream menu and deduplicates them', () => {
        const menu = [{year: 2013, season: '秋'}, {year: 2012, season: '春'}, {year: 2013, season: '秋'}]
        expect(mikanSeasonChoices(menu, 2013)).toEqual([{year: 2013, season: '秋'}])
        expect(mikanSeasonChoices(menu, 2014)).toEqual([])
        expect(menu).toHaveLength(3)
    })
    it('moves dimensions without mutating the saved policy', () => {
        const values = ['SUBTITLES', 'GROUP', 'RESOLUTION']
        expect(moveDimension(values, 2, 0)).toEqual(['RESOLUTION', 'SUBTITLES', 'GROUP'])
        expect(values).toEqual(['SUBTITLES', 'GROUP', 'RESOLUTION'])
        expect(moveDimension(values, -1, 0)).toEqual(values)
    })
    it('round trips Windows UNC and Linux mappings literally', () => {
        const mappings = [{remoteRoot: '/media', localRoot: 'D:\\Media'}, {remoteRoot: '\\\\nas\\share', localRoot: 'Z:\\share'}]
        expect(parseMappings(mappingText(mappings))).toEqual(mappings)
        expect(() => parseMappings('/media = D:\\Media')).toThrow()
        expect(() => parseMappings('/media => ')).toThrow()
    })
    it('exposes all operational defaults without sharing mutable arrays', () => {
        const first = defaultPolicy(), second = defaultPolicy()
        expect(first.dimensions).toEqual(['SUBTITLES', 'GROUP', 'RESOLUTION'])
        expect(first.minimumResolution).toBe(720)
        expect(first.requireChinese).toBe(true)
        expect(first.groups).toEqual([])
        first.groups.push('A')
        expect(second.groups).toEqual([])
    })
})
