import {useColorMode, useDark, useDebounceFn, useEventListener, useLocalStorage} from "@vueuse/core";
import {ref} from "vue";

/**
 * 保存登录信息
 */
let rememberThePassword = useLocalStorage('rememberThePassword', {
    remember: false,
    username: ''
})
let legacyRememberedCredentials = null
if (rememberThePassword.value && 'password' in rememberThePassword.value) {
    if (rememberThePassword.value.username && rememberThePassword.value.password) {
        legacyRememberedCredentials = {
            username: String(rememberThePassword.value.username),
            password: String(rememberThePassword.value.password)
        }
    }
    rememberThePassword.value = {
        remember: Boolean(rememberThePassword.value.remember),
        username: String(rememberThePassword.value.username || '')
    }
}

/**
 * 服务端会话状态（凭据仅保存在 HttpOnly Cookie 中）
 */
const authorization = ref('')
const csrfToken = ref('')

/**
 * 主题管理
 */
const {store} = useColorMode()

/**
 * 最大内容宽度
 */
const maxContentWidth = useLocalStorage('max-content-width', 1600);

/**
 * 显示评分
 */
const showScore = useLocalStorage('show-score', true)

/**
 * 按星期展示
 */
const showWeek = useLocalStorage("show-week", true)

/**
 * 显示视频列表
 */
const showPlaylist = useLocalStorage('show-playlist', true)

/**
 * 显示更新时间
 */
const showLastDownloadTime = useLocalStorage("show-last-download-time", true);

/**
 * 强调色
 */
const color = useLocalStorage('--el-color-primary', '#409eff')

/**
 * 改动强调色
 */
const colorChange = (v) => {
    const el = document.documentElement
    el.style.setProperty('--el-color-primary', v)
}

/**
 * 是否非移动设备
 */
const isNotMobile = ref(false)

/**
 * el-icon的class
 *
 * 自动适应移动布局
 */
const elIconClass = ref('')

/**
 * 主题初始化
 */
const initTheme = () => {
    /**
     * 夜间模式
     */
    useDark({
        onChanged: dark => {
            // 自动根据夜间模式修改沉浸式状态栏
            const meta = document.getElementById('themeColorMeta');
            meta.content = dark ? '#000000' : '#ffffff';
        }
    })

    // 修改强调色
    colorChange(color.value)
}

/**
 * 布局初始化
 */
const initLayout = () => {
    let app = document.querySelector('#app');

    // 设置最大布局宽度
    maxContentWidth.value = Math.max(maxContentWidth.value, 1200)

    app
        .style.maxWidth = `${maxContentWidth.value}px`

    const el = document.documentElement
    el.style.setProperty('--max-content-width', `${maxContentWidth.value}px`)

    // 是否非移动设备
    isNotMobile.value = app.offsetWidth > 800

    if (isNotMobile.value) {
        elIconClass.value = 'el-icon--left'
    } else {
        // 用以控制图标与文字的间距 当为移动设备时便不需要间距了
        elIconClass.value = ''
    }
}

/**
 * 初始化
 */
const init = () => {
    initTheme()
    initLayout()
}

const markAuthenticated = (csrf = '') => {
    authorization.value = 'session'
    csrfToken.value = csrf
}

const clearAuthentication = () => {
    authorization.value = ''
    csrfToken.value = ''
}

const initAuth = async () => {
    try {
        const response = await fetch(`${getBaseUrl()}api/v2/auth/csrf`, {
            credentials: 'include'
        })
        if (response.ok) {
            const body = await response.json()
            markAuthenticated(body.csrfToken)
            localStorage.removeItem('authorization')
            return true
        }
    } catch (e) {
    }
    try {
        const response = await fetch(`${getBaseUrl()}api/v2/auth/ip-login`, {
            method: 'POST',
            credentials: 'include'
        })
        if (response.ok) {
            const body = await response.json()
            markAuthenticated(body.csrfToken)
            localStorage.removeItem('authorization')
            return true
        }
    } catch (e) {
    }
    const rememberedCredentials = legacyRememberedCredentials
    legacyRememberedCredentials = null
    if (rememberedCredentials) {
        try {
            const response = await fetch(`${getBaseUrl()}api/v2/auth/login`, {
                method: 'POST',
                credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(rememberedCredentials)
            })
            if (response.ok) {
                const body = await response.json()
                markAuthenticated(body.csrfToken)
                localStorage.removeItem('authorization')
                return true
            }
        } catch (e) {
        }
    }
    const legacyToken = localStorage.getItem('authorization') || ''
    localStorage.removeItem('authorization')
    if (legacyToken) {
        try {
            const response = await fetch(`${getBaseUrl()}api/v2/auth/migrate`, {
                method: 'POST',
                credentials: 'include',
                headers: {'Authorization': legacyToken}
            })
            if (response.ok) {
                const body = await response.json()
                markAuthenticated(body.csrfToken)
                return true
            }
        } catch (e) {
        }
    }
    clearAuthentication()
    return false
}

/**
 * 当页面大小变化时重新计算一下布局
 * 对方法做节流处理
 */
useEventListener(window, 'resize', useDebounceFn(initLayout, 500))

const base64Encode = s => {
    const encoder = new TextEncoder();
    const data = encoder.encode(s);
    return window.btoa(String.fromCharCode(...data));
}

const getBaseUrl = () => {
    return new URL('.', document.baseURI).toString()
}

const toApiUrl = (path, params) => {
    const url = new URL(getBaseUrl())
    url.pathname += path
    url.search = new URLSearchParams(params).toString()
    return url.toString();
}

const toApiFile = filename => {
    return toApiUrl('api/file', {
        filename: base64Encode(filename)
    })
}

const toApiMedia = handle => `${getBaseUrl()}api/v2/media/${encodeURIComponent(handle)}`

export {
    rememberThePassword,
    authorization,
    csrfToken,
    store,
    maxContentWidth,
    showScore,
    showWeek,
    showPlaylist,
    showLastDownloadTime,
    color,
    colorChange,
    isNotMobile,
    elIconClass,
    init,
    initTheme,
    initLayout,
    initAuth,
    markAuthenticated,
    clearAuthentication,
    base64Encode,
    toApiUrl,
    toApiFile,
    toApiMedia,
    getBaseUrl
};
