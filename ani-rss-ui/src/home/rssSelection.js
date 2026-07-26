const text = value => typeof value === 'string' ? value.trim() : ''

/**
 * Providers historically emitted both a plain object and a Vue Ref. Keep the
 * boundary tolerant so an invalid upstream record cannot leave the editor in
 * a broken state.
 */
export const normalizeRssSelection = value => {
    const payload = value && typeof value === 'object' && value.value && typeof value.value === 'object'
        ? value.value : value
    if (!payload || typeof payload !== 'object') return null

    const subgroup = text(payload.subgroup)
    const url = text(payload.url)
    if (!subgroup || !url) return null

    let match = []
    if (Array.isArray(payload.match)) {
        match = payload.match
    } else if (typeof payload.match === 'string' && payload.match.trim()) {
        try {
            match = JSON.parse(payload.match)
        } catch {
            match = []
        }
    }

    return {
        subgroup,
        url,
        bgmUrl: text(payload.bgmUrl),
        match: Array.isArray(match)
            ? match.filter(item => typeof item === 'string' && item.trim()).map(item => item.trim())
            : []
    }
}

export const subgroupMatchRules = ({subgroup, match}) =>
    match.map(item => `{{${subgroup}}}:${item}`)

export const mikanSearchQuery = ani => {
    const query = text(ani?.mikanTitle) || text(ani?.title)
    const rssUrl = text(ani?.url)
    if (!rssUrl) return query

    try {
        const bangumiId = new URL(rssUrl).searchParams.get('bangumiId')
        return bangumiId ? `id: ${bangumiId}` : query
    } catch {
        return query
    }
}
