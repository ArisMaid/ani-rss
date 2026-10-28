export const dimensionLabels = {
    SUBTITLES: '字幕组合', GROUP: '字幕组', RESOLUTION: '分辨率',
    SOURCE: '来源类型', PACKAGING: '字幕封装', CODEC: '编码', CORRECTION: '修订版本'
}
export const jobLabels = {
    QUEUED: '等待执行', DOWNLOADING: '下载中', VERIFYING: '媒体校验', READY_TO_COMMIT: '等待安装',
    INSTALLING: '安装中', INSTALLED: '新版已安装', CLEANING_OLD: '清理旧版', COMPLETED: '完成',
    CLEANUP_PENDING: '新版可用，清理待重试', WAITING_EVIDENCE: '等待补证', WAITING_AI: '等待 AI',
    NEEDS_REVIEW: '需要修正', RETRY_WAIT: '等待重试', PAUSED: '暂停', CANCELLED: '已取消',
    FAILED: '失败', WAITING_LIBRARY: '完结档案，待关联媒体库', NOT_BETTER: '未优于现有版本', UPGRADE: '满足升级条件', REJECTED: '不满足硬条件',
    IDENTITY_CONFLICT: '身份冲突', ANALYZING: '解析中', MONITORING: '持续监控', NO_RESOURCE: '暂未找到资源', WAITING_PREMIERE: '等待播出', WAITING_PREMIERE_EVIDENCE: '首播日期待补证', UNAVAILABLE: '候选暂不可用', UNSUPPORTED: '不支持的资源格式', MERGED: '已归并资源身份'
}
export function moveDimension(values, from, to) {
    const result = [...values]
    if (from < 0 || from >= result.length || to < 0 || to >= result.length) return result
    result.splice(to, 0, result.splice(from, 1)[0])
    return result
}
export function parseMappings(text) {
    return text.split('\n').map(row => row.trim()).filter(Boolean).map(row => {
        const position = row.indexOf(' => ')
        if (position < 1 || !row.slice(position + 4).trim()) throw new Error('路径映射每行使用：远端根目录 => 本地根目录')
        return {remoteRoot: row.slice(0, position).trim(), localRoot: row.slice(position + 4).trim()}
    })
}
export const mappingText = values => (values || []).map(m => `${m.remoteRoot} => ${m.localRoot}`).join('\n')
export const seasonLabels = {冬: '冬季 · 1—3 月', 春: '春季 · 4—6 月', 夏: '夏季 · 7—9 月', 秋: '秋季 · 10—12 月'}
export function quarterDateRange(year, season) {
    const months = {冬: 1, 春: 4, 夏: 7, 秋: 10}, month = months[season]
    if (!Number.isInteger(year) || year < 1900 || year > 2200 || !month) return null
    const startDate = `${year}-${String(month).padStart(2, '0')}-01`
    const end = new Date(Date.UTC(year, month + 2, 0))
    return {startDate, endDate: end.toISOString().slice(0, 10)}
}
export function mikanSeasonChoices(seasons, year) {
    return (seasons || []).filter(s => s.year === year && seasonLabels[s.season])
        .filter((s, i, all) => all.findIndex(other => other.season === s.season) === i)
        .sort((a, b) => ['冬', '春', '夏', '秋'].indexOf(a.season) - ['冬', '春', '夏', '秋'].indexOf(b.season))
}
export function defaultPolicy() {
    return {
        name: '默认质量规则', revision: 1, dimensions: ['SUBTITLES', 'GROUP', 'RESOLUTION'],
        subtitles: ['CHS+CHT+JPN', 'CHS+JPN', 'CHS+CHT', 'CHS', 'CHT+JPN', 'CHT'], groups: [],
        groupAliases: {}, resolutions: [2160, 1080, 720, 480], sources: ['BD', 'WEB', 'TV', 'DVD'],
        packaging: ['SOFT', 'EXTERNAL', 'HARD'], codecs: ['AV1', 'HEVC', 'AVC'], minimumResolution: 720,
        requireChinese: true, requiredLanguages: [], requireBilingual: false, allowedGroups: [], blockedGroups: [],
        containers: ['mkv', 'mp4', 'avi', 'webm', 'ts']
    }
}

export function applyPolicyPreset(current, preset) {
    if(!preset?.policy) return current
    const next = JSON.parse(JSON.stringify(preset.policy))
    if(current.id) Object.assign(next, {id: current.id, revision: current.revision, name: current.name})
    return next
}
export function applyFansubPreference(current, groups) {
    const next = JSON.parse(JSON.stringify(current))
    const preferred = groups.map(group => group.name)
    next.groups = [...preferred, ...next.groups.filter(name => !preferred.includes(name) && !groups.some(group => group.aliases.includes(name)))]
    next.groupAliases = mergeFansubAliases(next.groupAliases, groups)
    if(!next.dimensions.includes('GROUP')) next.dimensions.push('GROUP')
    return next
}

// Values are the quality engine's canonical tokens, not filename regexes.
export const parameterOptions = {
    subtitles: ['CHS+CHT+JPN', 'CHS+JPN', 'CHS+CHT', 'CHS', 'CHT+JPN', 'CHT', 'JPN'],
    resolutions: [2160, 1080, 720, 480], sources: ['BD', 'WEB', 'TV', 'DVD'],
    packaging: ['SOFT', 'EXTERNAL', 'HARD'], codecs: ['AV1', 'HEVC', 'AVC'],
    requiredLanguages: ['CHS', 'CHT', 'JPN'], containers: ['mkv', 'mp4', 'avi', 'webm', 'ts']
}
const tokenLabels = {CHS:'简体', CHT:'繁体', JPN:'日文', BD:'蓝光', WEB:'网络片源', TV:'电视', DVD:'DVD',
    SOFT:'内封字幕', EXTERNAL:'外挂字幕', HARD:'内嵌字幕', HEVC:'H.265', AVC:'H.264', AV1:'AV1'}
export function parameterLabel(field, value) {
    if(field === 'resolutions') return `${value}p${value === 2160 ? ' / 4K' : ''}`
    const text = String(value).split('+').map(v => tokenLabels[v] || v).join(' + ')
    return text === String(value) ? text : `${text} · ${value}`
}
export const parameterPresets = [
    {id:'chs', name:'必须简体', description:'替换必须字幕语言为简体。', values:{requireChinese:true, requiredLanguages:['CHS'], requireBilingual:false}},
    {id:'cht', name:'必须繁体', description:'替换必须字幕语言为繁体。', values:{requireChinese:true, requiredLanguages:['CHT'], requireBilingual:false}},
    {id:'dual-chinese', name:'必须简繁', description:'必须同时包含简体与繁体。', values:{requireChinese:true, requiredLanguages:['CHS','CHT'], requireBilingual:false}},
    {id:'chs-jpn', name:'简日双语同屏', description:'必须简体、日文字幕及双语同屏证据；日语音轨不能代替字幕。', values:{requireChinese:true, requiredLanguages:['CHS','JPN'], requireBilingual:true}},
    {id:'1080', name:'1080p 优先', description:'最低 1080p，排序为 1080p → 2160p → 720p → 480p。', dimension:'RESOLUTION', values:{minimumResolution:1080, resolutions:[1080,2160,720,480]}},
    {id:'4k', name:'4K 优先', description:'最低 1080p，排序为 2160p → 1080p → 720p → 480p；不是仅接受 4K。', dimension:'RESOLUTION', values:{minimumResolution:1080, resolutions:[2160,1080,720,480]}},
    {id:'720', name:'允许 720p', description:'最低 720p，优先 1080p，其次 720p、2160p、480p。', dimension:'RESOLUTION', values:{minimumResolution:720, resolutions:[1080,720,2160,480]}},
    {id:'bd', name:'BD 优先', description:'来源排序 BD → WEB → TV → DVD。', dimension:'SOURCE', values:{sources:['BD','WEB','TV','DVD']}},
    {id:'web', name:'WEB 优先', description:'来源排序 WEB → BD → TV → DVD。', dimension:'SOURCE', values:{sources:['WEB','BD','TV','DVD']}},
    {id:'soft', name:'内封优先', description:'封装排序 内封 → 外挂 → 内嵌。', dimension:'PACKAGING', values:{packaging:['SOFT','EXTERNAL','HARD']}},
    {id:'external', name:'外挂优先', description:'封装排序 外挂 → 内封 → 内嵌。', dimension:'PACKAGING', values:{packaging:['EXTERNAL','SOFT','HARD']}},
    {id:'hard', name:'内嵌优先', description:'封装排序 内嵌 → 内封 → 外挂。', dimension:'PACKAGING', values:{packaging:['HARD','SOFT','EXTERNAL']}},
    {id:'hevc', name:'HEVC / H.265 优先', description:'编码排序 HEVC → AV1 → AVC。', dimension:'CODEC', values:{codecs:['HEVC','AV1','AVC']}},
    {id:'avc', name:'AVC / H.264 优先', description:'编码排序 AVC → HEVC → AV1。', dimension:'CODEC', values:{codecs:['AVC','HEVC','AV1']}},
    {id:'av1', name:'AV1 优先', description:'编码排序 AV1 → HEVC → AVC。', dimension:'CODEC', values:{codecs:['AV1','HEVC','AVC']}},
    {id:'mkv-mp4', name:'仅 MKV / MP4', description:'允许容器替换为 MKV 与 MP4，其他容器将被拒绝。', values:{containers:['mkv','mp4']}}
]
export function applyParameterPreset(current, preset) {
    if(!preset?.values) return current
    const next = JSON.parse(JSON.stringify({...current, ...preset.values}))
    if(preset.dimension && !next.dimensions.includes(preset.dimension)) next.dimensions.push(preset.dimension)
    return next
}
export function mergeFansubAliases(current, groups) {
    const key = value => value.normalize('NFKC').trim().toLowerCase()
    const custom = new Set(Object.keys(current || {}).map(key))
    return {...Object.fromEntries(groups.flatMap(g => g.aliases.filter(a => !custom.has(key(a))).map(a => [a, g.name]))), ...current}
}
