import {describe, expect, it} from 'vitest'
import {defaultPolicy, mappingText, moveDimension, parseMappings, quarterDateRange, mikanSeasonChoices, applyPolicyPreset, applyFansubPreference, applyParameterPreset, parameterPresets, mergeFansubAliases, parameterLabel} from './upgrade.js'
describe('upgrade settings and rule controls', () => {
    it('applies a full preset to a draft while preserving the existing identity and revision', () => {
        const current = {...defaultPolicy(), id: 'saved', revision: 9, name: '我的规则'}
        const preset = {policy: {...defaultPolicy(), name: '预设', minimumResolution: 1080, requiredLanguages: ['CHS']}}
        const result = applyPolicyPreset(current, preset)
        expect(result).toMatchObject({id: 'saved', revision: 9, name: '我的规则', minimumResolution: 1080, requiredLanguages: ['CHS']})
        result.groups.push('edited')
        expect(current.minimumResolution).toBe(720)
        expect(preset.policy.groups).toEqual([])
        expect(applyPolicyPreset(defaultPolicy(), preset).name).toBe('预设')
    })
    it('inserts preferred groups ahead of custom fallbacks and preserves custom aliases', () => {
        const current = {...defaultPolicy(), groups: ['桜都', '后备组'], groupAliases: {Custom: '后备组'}}
        const groups = [{name: '桜都字幕组', aliases: ['桜都']}, {name: 'SweetSub', aliases: []}]
        const result = applyFansubPreference(current, groups)
        expect(result.groups).toEqual(['桜都字幕组', 'SweetSub', '后备组'])
        expect(result.groupAliases).toEqual({'桜都': '桜都字幕组', Custom: '后备组'})
        expect(current.groups).toEqual(['桜都', '后备组'])
    })
    it('applies parameter chips without resetting unrelated restrictions or dimension priority', () => {
        const current = {...defaultPolicy(), id:'saved', revision:4, groups:['Custom'], blockedGroups:['Blocked'], requiredLanguages:['CHT']}
        const next = applyParameterPreset(current, parameterPresets.find(p => p.id === 'hevc'))
        expect(next).toMatchObject({id:'saved', revision:4, groups:['Custom'], blockedGroups:['Blocked'], requiredLanguages:['CHT'], codecs:['HEVC','AV1','AVC']})
        expect(next.dimensions).toEqual([...current.dimensions, 'CODEC'])
        expect(applyParameterPreset(next, parameterPresets.find(p => p.id === 'avc')).dimensions).toEqual(next.dimensions)
        next.groups.push('draft')
        expect(current.groups).toEqual(['Custom'])
        expect(parameterLabel('packaging', 'SOFT')).toContain('内封')
        expect(parameterLabel('resolutions', 2160)).toContain('4K')
        expect(mergeFansubAliases({'ｄｍｇ':'Custom'}, [{name:'动漫国字幕组', aliases:['DMG','動漫國']}])).toEqual({'ｄｍｇ':'Custom', '動漫國':'动漫国字幕组'})
    })
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
