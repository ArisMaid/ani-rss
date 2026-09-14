import api from "@/js/api.js";
import {base64Encode, markAuthenticated} from "./global.js";

const withQuery = (path, params) => {
    const url = new URL(path, document.baseURI)
    url.search = new URLSearchParams(params).toString()
    return url.toString()
}

/**
 * 获取设置
 * @returns {Promise<any>}
 */
export let config = () => api.get('api/v2/config')

/**
 * 修改设置
 * @param config 设置
 * @returns {Promise<any>}
 */
export let setConfig = (config) => api.put('api/v2/config', config);

/**
 * 订阅列表
 * @returns {Promise<any>}
 */
export let listAni = (options = {}) => api.post('api/listAni', '', options)

/**
 * 添加订阅
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let addAni = (ani, options = {}) => api.post('api/addAni', ani, options)

/**
 * 修改订阅
 * @param move 自动移动本地文件
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let setAni = (move, ani) => api.post(withQuery('api/setAni', {move}), ani)

/**
 * 删除订阅
 * @param deleteFiles 同时删除本地文件
 * @param ids ids
 * @returns {Promise<any>}
 */
export let deleteAni = (deleteFiles, ids) => api.post(withQuery('api/deleteAni', {deleteFiles}), ids)

export let deleteSubscriptions = (subscriptionIds, deleteFiles = true) =>
    api.post('api/v2/subscriptions/delete', {subscriptionIds, deleteFiles})

export let listSubscriptionsV2 = () => api.get('api/v2/subscriptions')

export let listOwnerships = () => api.get('api/v2/ownership')

export let listOwnershipCandidates = () => api.get('api/v2/ownership/candidates')

export let adoptOwnership = (candidate) => api.post('api/v2/ownership/adopt', candidate)

export let listQuarantine = () => api.get('api/v2/ownership/quarantine')

export let restoreQuarantine = (operationId) =>
    api.post(withQuery('api/v2/ownership/quarantine/restore', {operationId}))

export let purgeExpiredQuarantine = () => api.post('api/v2/ownership/quarantine/purge-expired')

export let purgeQuarantine = (operationId) =>
    api.post('api/v2/ownership/quarantine/purge', {operationId, confirmed: true})

/**
 * 关于
 * @returns {Promise<any>}
 */
export let about = () => api.get('api/v2/about')

/**
 * 更新
 * @returns {Promise<any>}
 */
export let update = () => api.post('api/v2/update')

export let webuiUpdate = () => api.post('api/webui/update')
export let webuiGetUpdate = () => api.post('api/webui/getUpdate')
export let webuiDelete = () => api.post('api/webui/delete')

/**
 * 获取Mikan番剧列表
 * @param text 关键词
 * @param season 季度
 * @returns {Promise<any>}
 */
export let mikan = (text, season, options = {}) =>
    api.post(withQuery('api/mikan', {text}), season, options)

/**
 * Loads public scores after the Mikan season list is rendered.
 * @param {string[]} mikanIds Mikan bangumi ids from the trusted list response
 * @returns {Promise<any>}
 */
export let mikanScores = (mikanIds, options = {}) => api.post('api/mikanScores', mikanIds, options)

/**
 * 获取Mikan番剧的字幕组列表
 * @param url 番剧url
 * @returns {Promise<any>}
 */
export let mikanGroup = (url, options = {}) => api.post(withQuery('api/mikanGroup', {url}), null, options)

/**
 * 获取AniBT番剧的字幕组列表
 * @param url 番剧url
 * @returns {Promise<any>}
 */
export let aniBTGroup = (url) => api.post(withQuery('api/aniBTGroup', {bgmId: url}))

/**
 * 获取AnimeGarden番剧列表
 * @returns {Promise<any>}
 */
export let animeGardenList = (bgmUrl, options = {}) =>
    api.post(withQuery('api/animeGardenList', {bgmUrl}), '', options)

export let animeGardenEnrichment = (subjectIds, options = {}) =>
    api.post('api/animeGardenEnrichment', subjectIds, options)

/**
 * 获取AnimeGarden番剧的字幕组列表
 * @param bgmId 番剧ID
 * @returns {Promise<any>}
 */
export let animeGardenGroup = (bgmId, options = {}) =>
    api.post(withQuery('api/animeGardenGroup', {bgmId}), null, options)

/**
 * 刷新全部订阅
 * @returns {Promise<any>}
 */
export let refreshAll = () => api.post('api/refreshAll')

/**
 * 刷新订阅
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let refreshAni = (ani) => api.post('api/refreshAni', ani)

/**
 * 将RSS转换为订阅
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let rssToAni = (ani, options = {}) => api.post('api/rssToAni', ani, options)

/**
 * 预览订阅
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let previewAni = (ani) => api.post('api/previewAni', ani)

/**
 * 日志
 * @returns {Promise<any>}
 */
export let logs = () => api.post('api/logs')

/**
 * 清理日志
 * @returns {Promise<any>}
 */
export let clearLogs = () => api.post('api/clearLogs')

/**
 * 获取TMDB标题
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let getThemoviedbName = (ani) => api.post('api/getThemoviedbName', ani)

/**
 * 获取TMDB剧集组
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let getThemoviedbGroup = (ani) => api.post('api/getThemoviedbGroup', ani)

/**
 * 测试通知
 * @param notificationConfig 通知设置
 * @returns {Promise<any>}
 */
export let testNotification = (notificationConfig) => api.post('api/testNotification', notificationConfig)

/**
 * 新的通知
 * @returns {Promise<any>}
 */
export let newNotification = () => api.post('api/newNotification')

/**
 * 获取BGM标题
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let getBgmTitle = (ani) => api.post('api/getBgmTitle', ani)


/**
 * 搜索BGM条目
 * @param name 关键词
 * @returns {Promise<any>}
 */
export let searchBgm = (name) => api.post(withQuery('api/searchBgm', {name}))

/**
 * 代理测试
 * @param url url
 * @param config 设置
 * @returns {Promise<any>}
 */
export let testProxy = (url, config) => api.post('api/v2/config/proxy-test', {url, config})

/**
 * 下载列表
 * @returns {Promise<any>}
 */
export let torrentsInfos = (options = {}) => api.post('api/torrentsInfos', '', options)

/**
 * 订单号校验
 * @param config 设置
 * @returns {Promise<any>}
 */
export let verifyNo = (config) => api.post('api/verifyNo', config)

/**
 * 更新总集数
 * @param force 强制
 * @param ids ids
 * @returns {Promise<any>}
 */
export let updateTotalEpisodeNumber = (force, ids) => api.post(withQuery('api/updateTotalEpisodeNumber', {force}), ids)

/**
 * 批量刮削
 * @param force 强制
 * @param ids ids
 * @returns {Promise<any>}
 */
export let batchScrape = (force, ids) => api.post(withQuery('api/batchScrape', {force}), ids)

/**
 * 批量 启用/禁用 订阅
 * @param value true/false
 * @param ids ids
 * @returns {Promise<any>}
 */
export let batchEnable = (value, ids) => api.post(withQuery('api/batchEnable', {value}), ids)

/**
 * 导入订阅
 * @param anis 订阅列表
 * @returns {Promise<any>}
 */
export let importAni = (anis) => api.post('api/importAni', anis)

/**
 * 停止服务
 * @param status 0:重启 2:关闭
 * @returns {Promise<any>}
 */
export let stop = (status) => api.post(withQuery('api/stop', {status}))

/**
 * 刷新封面
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let refreshCover = (ani) => api.post('api/refreshCover', ani)

/**
 * 获取评分
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let rate = (ani) => api.post('api/rate', ani)

/**
 * 进行评分
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let setRate = (ani) => api.post('api/setRate', ani)

/**
 * 获取下载位置
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let downloadPath = (ani) => api.post('api/downloadPath', ani)

/**
 * 刮削
 * @param force 强制 true/false
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let scrape = (force, ani) => api.post(withQuery('api/scrape', {force}), ani)

/**
 * 获取当前BGM账号信息
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let meBgm = (ani) => api.post('api/meBgm', ani)

/**
 * 更新trackers
 * @param config 设置
 * @returns {Promise<any>}
 */
export let trackersUpdate = (config) => api.post('api/trackersUpdate', config)

/**
 * 获取Emby媒体库
 * @param config 设置
 * @returns {Promise<any>}
 */
export let getEmbyViews = (config) => api.post('api/getEmbyViews', config)

/**
 * 清理缓存
 * @returns {Promise<any>}
 */
export let clearCache = () => api.post('api/clearCache')

/**
 * 下载器测试
 * @param config 设置
 * @returns {Promise<any>}
 */
export let downloadLoginTest = (config) => api.post('api/v2/config/downloader-test', config)

export let revealApiKey = () => api.post('api/v2/config/api-key/reveal')

export let rotateApiKey = () => api.post('api/v2/config/api-key/rotate')

/**
 * 获取TG最近消息
 * @param notificationConfig 通知配置
 * @returns {Promise<any>}
 */
export let getTgUpdates = (notificationConfig) => api.post('api/getTgUpdates', notificationConfig)

/**
 * 登录
 * @param user
 * @returns {Promise<any>}
 */
export let login = (user) => {
    user = JSON.parse(JSON.stringify(user))
    return api.post('api/v2/auth/login', user).then(res => {
        markAuthenticated(res.data.csrfToken)
        return res
    })
}

export let bgmOAuthState = () => api.post('api/v2/auth/oauth-state/bgm')

export let logout = () => api.post('api/v2/auth/logout')

const MAX_CONCURRENT_IMAGE_REQUESTS = 6
const IMAGE_QUEUE_CAPACITY = 12
const IMAGE_QUEUE_WAIT_TIMEOUT_MILLIS = 3_000
const IMAGE_REQUEST_TIMEOUT_MILLIS = 15_000
const imageRequests = new Map()
const imageQueue = []
let activeImageRequests = 0

const monotonicNow = () => {
    if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
        return performance.now()
    }
    return Date.now()
}

const imageError = (code, message) => {
    const error = new Error(message)
    error.code = code
    error.status = 0
    return error
}

const abortedImageError = () => imageError('REQUEST_ABORTED', '图片请求已取消')
const busyImageError = () => imageError('IMAGE_QUEUE_BUSY', '图片加载繁忙，请稍后重试')
const queueTimeoutImageError = () => imageError('IMAGE_QUEUE_TIMEOUT', '图片加载排队超时，请稍后重试')
const requestTimeoutImageError = () => imageError('IMAGE_REQUEST_TIMEOUT', '图片加载超时，请稍后重试')

const clearTimer = timer => {
    if (timer !== undefined) clearTimeout(timer)
}

const removeMappedTask = task => {
    if (imageRequests.get(task.url) === task) imageRequests.delete(task.url)
}

const removeQueuedTask = task => {
    const index = imageQueue.indexOf(task)
    if (index >= 0) imageQueue.splice(index, 1)
}

const settleConsumer = (task, consumer, error, value) => {
    if (consumer.settled) return
    consumer.settled = true
    task.consumers.delete(consumer)
    consumer.signal?.removeEventListener('abort', consumer.onAbort)
    if (error) consumer.reject(error)
    else consumer.resolve(value)
}

const settleConsumers = (task, error, value) => {
    for (const consumer of [...task.consumers]) {
        settleConsumer(task, consumer, error, value)
    }
}

const abortOrphanedTask = task => {
    if (task.consumers.size > 0 || task.status === 'done') return
    clearTimer(task.waitTimer)
    clearTimer(task.totalTimer)
    removeMappedTask(task)
    task.controller.abort()
    if (task.status === 'queued') {
        task.status = 'cancelled'
        removeQueuedTask(task)
    } else if (task.status === 'running') task.orphaned = true
}

const expireQueuedTask = task => {
    if (task.status !== 'queued') return
    if (monotonicNow() < task.waitDeadlineAt) {
        task.waitTimer = setTimeout(() => expireQueuedTask(task),
            Math.max(1, task.waitDeadlineAt - monotonicNow()))
        return
    }
    task.status = 'expired'
    clearTimer(task.waitTimer)
    removeQueuedTask(task)
    removeMappedTask(task)
    task.controller.abort()
    settleConsumers(task, queueTimeoutImageError())
    runImageQueue()
}

const expireRunningTask = task => {
    if (task.status !== 'running') return
    if (monotonicNow() < task.totalDeadlineAt) {
        task.totalTimer = setTimeout(() => expireRunningTask(task),
            Math.max(1, task.totalDeadlineAt - monotonicNow()))
        return
    }
    task.timedOut = true
    clearTimer(task.totalTimer)
    removeMappedTask(task)
    task.controller.abort()
    settleConsumers(task, requestTimeoutImageError())
}

const finishImageTask = (task, error, value) => {
    if (task.status === 'done') return
    task.status = 'done'
    clearTimer(task.waitTimer)
    clearTimer(task.totalTimer)
    removeMappedTask(task)
    if (!task.timedOut && !task.orphaned) settleConsumers(task, error, value)
    activeImageRequests--
    runImageQueue()
}

const executeImageTask = task => {
    let value
    let error
    Promise.resolve()
        .then(() => api.post('api/v2/images', {url: task.url}, {
            silent: true,
            signal: task.controller.signal
        }))
        .then(result => {
            value = result
        }, cause => {
            error = cause
        })
        .finally(() => finishImageTask(task, error, value))
}

const runImageQueue = () => {
    while (activeImageRequests < MAX_CONCURRENT_IMAGE_REQUESTS && imageQueue.length) {
        const task = imageQueue.shift()
        if (task.status !== 'queued' || task.consumers.size === 0) continue
        if (monotonicNow() >= task.waitDeadlineAt || monotonicNow() >= task.totalDeadlineAt) {
            expireQueuedTask(task)
            continue
        }
        task.status = 'running'
        clearTimer(task.waitTimer)
        task.totalTimer = setTimeout(() => expireRunningTask(task),
            Math.max(1, task.totalDeadlineAt - monotonicNow()))
        activeImageRequests++
        executeImageTask(task)
    }
}

const queuedTaskCount = () => imageQueue.reduce((count, task) =>
    count + (task.status === 'queued' ? 1 : 0), 0)

const refreshTaskDeadline = task => {
    if (!task || task.status === 'done') return undefined
    const now = monotonicNow()
    if (task.status === 'queued' && now >= task.waitDeadlineAt) expireQueuedTask(task)
    if (task.status === 'running' && now >= task.totalDeadlineAt) expireRunningTask(task)
    return task.status === 'done' || task.status === 'cancelled' || task.status === 'expired'
        ? undefined : task
}

const subscribeToImageTask = (task, signal) => new Promise((resolve, reject) => {
    const consumer = {
        resolve,
        reject,
        signal,
        settled: false,
        onAbort: undefined
    }
    consumer.onAbort = () => {
        settleConsumer(task, consumer, abortedImageError())
        abortOrphanedTask(task)
        runImageQueue()
    }
    task.consumers.add(consumer)
    if (signal) {
        if (signal.aborted) {
            consumer.onAbort()
            return
        }
        signal.addEventListener('abort', consumer.onAbort, {once: true})
    }
})

export let cacheImage = (url, {signal} = {}) => {
    if (signal?.aborted) return Promise.reject(abortedImageError())

    runImageQueue()
    let task = refreshTaskDeadline(imageRequests.get(url))
    if (!task) {
        if (queuedTaskCount() >= IMAGE_QUEUE_CAPACITY) {
            return Promise.reject(busyImageError())
        }
        const enqueuedAt = monotonicNow()
        task = {
            url,
            controller: new AbortController(),
            consumers: new Set(),
            status: 'queued',
            orphaned: false,
            timedOut: false,
            enqueuedAt,
            waitDeadlineAt: enqueuedAt + IMAGE_QUEUE_WAIT_TIMEOUT_MILLIS,
            totalDeadlineAt: enqueuedAt + IMAGE_REQUEST_TIMEOUT_MILLIS,
            waitTimer: undefined,
            totalTimer: undefined
        }
        imageRequests.set(url, task)
        imageQueue.push(task)
        task.waitTimer = setTimeout(() => expireQueuedTask(task),
            IMAGE_QUEUE_WAIT_TIMEOUT_MILLIS)
    }

    const promise = subscribeToImageTask(task, signal)
    runImageQueue()
    return promise
}

/**
 * 测试IP白名单
 * @returns {Promise<Response>}
 */
export let testIpWhitelist = () => api.post('api/testIpWhitelist')

/**
 * 获取视频列表
 * @param ani 订阅
 * @returns {Promise<any>}
 */
export let playList = (ani) => api.post('api/playList', ani)

/**
 * 获取内封字幕
 * @param filename 视频文件路径
 * @returns {Promise<any>}
 */
export let getSubtitles = (filename, options = {}) => {
    return api.post(withQuery('api/getSubtitles', {filename: base64Encode(filename)}), null, options);
}

/**
 * 开始下载合集
 * @param info 合集
 * @returns {Promise<any>}
 */
export let startCollection = (info) => api.post('api/startCollection', info)

/**
 * 预览合集
 * @param info 合集
 * @returns {Promise<any>}
 */
export let previewCollection = (info) => api.post('api/previewCollection', info)

/**
 * 获取合集字幕组
 * @param info 合集
 * @returns {Promise<any>}
 */
export let getCollectionSubgroup = (info) => api.post('api/getCollectionSubgroup', info)

/**
 * 将指定id的BGM番剧转换为订阅
 * @param id BGM的ID
 * @returns {Promise<any>}
 */
export let getAniBySubjectId = (id) => api.post(withQuery('api/getAniBySubjectId', {id}))

/**
 * 获取AniBT番剧列表
 * @param season 季度
 * @param bgmUrl
 * @param text
 * @returns {Promise<any>}
 */
export let aniBT = (season, bgmUrl, text) => api.post('api/aniBT', {
    season,
    bgmUrl,
    title: text
})

/**
 * 删除缓存的种子
 * @param id 订阅id
 * @param hash 种子hash
 * @returns {Promise<any>}
 */
export let deleteTorrent = (id, hash) => api.post(withQuery('api/deleteTorrent', {id, hash}))

export let stageRestore = async (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('api/v2/restore', formData, {silent: true}).then(response => response.data)
}

export let externalMediaHandle = (handle) =>
    api.post(`api/v2/media/${encodeURIComponent(handle)}/external`)

export let confirmRestore = (operationId) =>
    api.post(`api/v2/restore/${encodeURIComponent(operationId)}/confirm`, null, {silent: true})
        .then(res => res.data)

export let restoreStatus = (operationId) =>
    api.get(`api/v2/restore/${encodeURIComponent(operationId)}`, {silent: true}).then(res => res.data)

export let ping = () => api.get("api/ping")
