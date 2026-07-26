import {ElMessage} from "element-plus";
import {clearAuthentication, csrfToken} from "@/js/global.js";

/**
 * @typedef {object} ApiResponse
 * @property {number} code
 * @property {string} message
 * @property {any} data
 * @property {number=} t
 */

/**
 * @typedef {Error & {
 *   code?: string,
 *   operationId?: string,
 *   status?: number,
 *   problem?: Record<string, any>
 * }} ApiError
 */

let post = async (url, body, options = {}) => {
    return await fetch_(url, 'POST', body, options);
}

let get = async (url, options = {}) => {
    return await fetch_(url, 'GET', '', options);
}

let del = async (url, body, options = {}) => {
    return await fetch_(url, 'DELETE', body, options);
}

let put = async (url, body, options = {}) => {
    return await fetch_(url, 'PUT', body, options);
}

let fetch_ = async (url, method, body, options = {}) => {
    /** @type {Record<string, string>} */
    let headers = {}
    const isForm = typeof FormData !== 'undefined' && body instanceof FormData
    if (body && !isForm) {
        headers['Content-Type'] = 'application/json'
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken.value && !isCsrfExempt(url)) {
        headers['X-CSRF-Token'] = csrfToken.value
    }
    let response
    try {
        response = await fetch(url, {
            method: method,
            body: body ? (isForm ? body : JSON.stringify(body)) : null,
            headers: headers,
            credentials: 'include'
        })
    } catch (cause) {
        const error = /** @type {ApiError} */ (new Error('网络请求失败'))
        error.code = 'NETWORK_ERROR'
        error.status = 0
        error.cause = cause
        if (!options.silent) ElMessage.error(error.message)
        throw error
    }
    /** @type {any} */
    let result
    try {
        result = await response.json()
    } catch {
        result = {}
    }
    if (!result || typeof result !== 'object') result = {}
    if (!response.ok) {
        const message = result.detail || result.message || `请求失败 (${response.status})`
        if (!options.silent) ElMessage.error(message)
        if (response.status === 401 || response.status === 403) {
            clearAuthentication()
            setTimeout(() => location.reload(), 1000)
        }
        const error = /** @type {ApiError} */ (new Error(message))
        error.code = result.code
        error.operationId = result.operationId
        error.status = response.status
        error.problem = result
        throw error
    }

    // Legacy endpoints use a numeric `code`; RFC 9457 errors use a string
    // `code`, so presence alone cannot distinguish the two response shapes.
    const legacyResult = typeof result.code === 'number' && 'message' in result
    if (!legacyResult) {
        return {code: response.status, message: '', data: result, t: Date.now()}
    }

    let {code, message, t} = result
    if (t !== undefined && !checkTimestampRange(t, true)) {
        console.warn('与服务端时差超过30分钟')
    }
    if (code >= 200 && code < 300) {
        return result
    }
    if (!options.silent) ElMessage.error(message)
    if (code === 401 || code === 403) {
        clearAuthentication()
        setTimeout(() => location.reload(), 1000)
    }
    throw new Error(message)
}

export default {post, get, del, put}

let checkTimestampRange = (timestamp, isMilli = true) => {
    const ts = Math.floor(Number(timestamp));
    if (Number.isNaN(ts)) return false;
    const targetTime = isMilli ? ts : ts * 1000;
    const now = Date.now();
    // 30 分钟
    const range = 30 * 60 * 1000;
    const diff = Math.abs(now - targetTime);
    return diff <= range;
}

const isCsrfExempt = value => {
    const path = new URL(value, document.baseURI).pathname
    return path === '/api/v2/auth/login' || path === '/api/v2/auth/setup'
}
