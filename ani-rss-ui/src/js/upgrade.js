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
