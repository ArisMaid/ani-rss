import {aniData} from './ani.js'

export function batchRequests(items, type) {
    const grouped = new Map()
    for (const value of items) {
        const source = typeof value === 'string' ? JSON.parse(value) : value
        const url = new URL(source.rss)
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error('RSS 地址必须使用 HTTP(S)')
        const subject = source.bgmId || (source.bgmUrl || '').match(/\/subject\/(\d+)/)?.[1]
        const id = type === 'mikan' ? url.searchParams.get('bangumiId') : subject || url.searchParams.get('bgmId')
        const key = id ? `${type}:${id}` : url.href
        if (!grouped.has(key)) grouped.set(key, [])
        const group = grouped.get(key)
        if (!group.some(item => item.rss === source.rss)) group.push(source)
    }
    return [...grouped.values()].map(sources => {
        const primary = sources[0]
        const request = {...JSON.parse(JSON.stringify(aniData)), type, url: primary.rss,
            subgroup: primary.label || primary.name || '', bgmUrl: primary.bgmUrl || (primary.bgmId ? `https://bgm.tv/subject/${primary.bgmId}` : '')}
        request.standbyRssList = sources.slice(1).map(source => ({label: source.label || source.name || '', url: source.rss, offset: 0}))
        request.match = [...new Set(sources.flatMap(source => (source.match || []).map(rule => `{{${source.label || source.name || ''}}}:${rule}`)))]
        return request
    })
}
