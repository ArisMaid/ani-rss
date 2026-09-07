import {existsSync, readFileSync, writeFileSync} from 'node:fs'
import {createServer} from 'node:http'
import {cpus} from 'node:os'
import {basename, extname, resolve, sep} from 'node:path'
import {performance} from 'node:perf_hooks'
import {execFileSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import {configData} from '../src/js/config.js'

const readArgument = (name, fallback) => {
  const index = process.argv.indexOf(name)
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback
}

const staticRoot = resolve(readArgument('--root', '.verify-dist'))
const outputPath = resolve(readArgument('--output', 'docs/performance-data/w7-browser.json'))
const expectedScenarios = (readArgument(
    '--expected',
    'login-cold,login-hot,home-cold,home-hot,subscriptions-cold,subscriptions-hot,player,settings'
)).split(',').filter(Boolean)

const runId = `w7-browser-${new Date().toISOString().replaceAll(':', '-')}-${randomUUID().slice(0, 8)}`
const commit = execFileSync('git', ['rev-parse', 'HEAD'], {encoding: 'utf8'}).trim()
const gitStatus = execFileSync('git', ['status', '--porcelain'], {encoding: 'utf8'})
const statusLines = gitStatus.split(/\r?\n/).map(line => line.trimEnd()).filter(Boolean)
const dirty = statusLines.some(line => !line.startsWith('??'))
const untrackedFiles = statusLines.filter(line => line.startsWith('??')).map(line => line.slice(3))
const requests = []
const completed = new Map()

// The capture command is intentionally attached after navigation so it can be
// reused for already-open sessions. This tiny fixture-only probe preserves
// startup page errors and console errors that happen before that attachment.
const browserProbe = `<script>
(() => {
  const probe = window.__w7Probe = {pageErrors: [], consoleErrors: [], chunk404s: []}
  const stringify = value => {
    try { return typeof value === 'string' ? value : JSON.stringify(value) }
    catch { return String(value) }
  }
  window.addEventListener('error', event => {
    const target = event.target
    if (target && (target.tagName === 'SCRIPT' || target.tagName === 'LINK')) {
      probe.chunk404s.push(target.src || target.href || 'resource')
      return
    }
    probe.pageErrors.push(String(event.error?.message || event.message || event.error || 'window error'))
  }, true)
  window.addEventListener('unhandledrejection', event => {
    probe.pageErrors.push(String(event.reason?.message || event.reason || 'unhandled rejection'))
  })
  const originalError = console.error.bind(console)
  console.error = (...args) => {
    probe.consoleErrors.push(args.map(stringify).join(' '))
    originalError(...args)
  }
})()
</script>`

const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.gif': 'image/gif',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp'
}

const send = (response, status, body, headers = {}) => {
  const payload = Buffer.isBuffer(body) ? body : Buffer.from(body)
  response.writeHead(status, {
    'Cache-Control': 'no-store',
    'Content-Length': payload.length,
    ...headers
  })
  response.end(payload)
  return payload.length
}

const sendJson = (response, status, value) => send(response, status,
    Buffer.from(JSON.stringify(value)), {'Content-Type': 'application/json; charset=utf-8'})

const parseModePath = pathname => {
  const match = pathname.match(/^\/__w7\/(unauth|auth)(?:\/(.*))?$/)
  if (!match) return null
  return {mode: match[1], relativePath: match[2] || ''}
}

const fixtureItem = {
  id: 'w7-fixture-subscription',
  title: 'W7 合成订阅',
  enable: true,
  currentEpisodeNumber: 1,
  totalEpisodeNumber: 12,
  releaseDate: '2026-09-07',
  lastDownloadTime: 0,
  cover: '',
  subgroup: 'W7 Fixture',
  standbyRssList: [],
  score: 8.5,
  bgmUrl: 'https://bgm.tv/subject/10000',
  season: 1,
  ova: false,
  sort: 1,
  pinyin: 'w7',
  pinyinInitials: 'w',
  procrastinating: false
}

const apiResponse = (mode, method, relativePath, url) => {
  if (relativePath === 'api/v2/auth/csrf' && method === 'GET') {
    return mode === 'auth'
      ? {status: 200, body: {csrfToken: 'w7-csrf-token'}}
      : {status: 401, body: {code: 'UNAUTHENTICATED', message: 'fixture login required'}}
  }
  if (relativePath === 'api/v2/auth/ip-login' && method === 'POST') {
    return {status: 401, body: {code: 'UNAUTHENTICATED', message: 'fixture login required'}}
  }
  if (relativePath === 'api/v2/auth/login' && method === 'POST') {
    return {status: 200, body: {csrfToken: 'w7-csrf-token'}}
  }
  if (relativePath === 'api/listAni' && method === 'POST') {
    return {
      status: 200,
      body: {
        weekList: [{weekLabel: '星期一', items: [fixtureItem]}],
        total: 1,
        releaseDateList: ['2026-09']
      }
    }
  }
  if (relativePath === 'api/v2/config' && method === 'GET') {
    return {status: 200, body: {...configData, login: {...configData.login}}}
  }
  if (relativePath === 'api/torrentsInfos' && method === 'POST') {
    return {status: 200, body: []}
  }
  if (relativePath === 'api/playList' && method === 'POST') {
    return {
      status: 200,
      body: [{
        name: 'w7-fixture.mp4',
        filename: 'w7-fixture.mp4',
        extName: 'mp4',
        subtitles: [],
        lastModify: Date.now(),
        formatSize: '1 KB'
      }]
    }
  }
  if (relativePath === 'api/getSubtitles' && method === 'POST') {
    return {status: 200, body: []}
  }
  if (relativePath.startsWith('api/v2/media/')) {
    if (relativePath.endsWith('/external') && method === 'POST') {
      return {status: 200, body: {handle: 'w7-fixture-handle'}}
    }
    return {status: 200, body: Buffer.from('W7-FIXTURE-MEDIA')}
  }
  if (relativePath === 'api/custom.css' && method === 'GET') {
    return {status: 200, body: '', headers: {'Content-Type': 'text/css; charset=utf-8'}}
  }
  if (relativePath === 'api/custom.js' && method === 'GET') {
    return {status: 200, body: '', headers: {'Content-Type': 'text/javascript; charset=utf-8'}}
  }
  if (relativePath === 'api/ping' && method === 'GET') {
    return {status: 200, body: {status: 'ok'}}
  }
  if (relativePath === 'api/about' && method === 'GET') {
    return {status: 200, body: {version: configData.version}}
  }
  if (relativePath.startsWith('api/')) {
    return {status: 200, body: {}}
  }
  return null
}

const safeStaticPath = relativePath => {
  const decoded = decodeURIComponent('/' + relativePath).replaceAll('/', sep)
  const candidate = resolve(staticRoot, `.${decoded}`)
  const rootWithSeparator = staticRoot.endsWith(sep) ? staticRoot : staticRoot + sep
  if (candidate !== staticRoot && !candidate.startsWith(rootWithSeparator)) return null
  return candidate
}

const serveStatic = (request, response, relativePath) => {
  const requested = relativePath || 'index.html'
  const filePath = safeStaticPath(requested)
  if (!filePath || !existsSync(filePath)) return {status: 404, bytes: send(response, 404, 'Not found')}

  const extension = extname(filePath).toLowerCase()
  const acceptsGzip = /(?:^|,)\s*gzip(?:\s*;|\s*,|$)/i.test(request.headers['accept-encoding'] || '')
  const compressedPath = `${filePath}.gz`
  const useGzip = acceptsGzip && (extension === '.js' || extension === '.css') && existsSync(compressedPath)
  const body = extension === '.html' && !useGzip
    ? Buffer.concat([Buffer.from(browserProbe), readFileSync(filePath)])
    : readFileSync(useGzip ? compressedPath : filePath)
  const headers = {
    'Content-Type': contentTypes[extension] || 'application/octet-stream',
    'Cache-Control': extension === '.html'
      ? 'no-cache'
      : 'public, max-age=31536000, immutable',
    'ETag': `"${basename(filePath)}-${body.length}"`,
    ...(useGzip ? {'Content-Encoding': 'gzip', 'Vary': 'Accept-Encoding'} : {})
  }
  return {status: 200, bytes: send(response, 200, body, headers), contentEncoding: useGzip ? 'gzip' : 'identity'}
}

const writeReport = () => {
  const output = {
    schemaVersion: 1,
    runId,
    commit,
    dirty,
    dirtyTracked: dirty,
    untrackedFiles,
    measurementKind: 'browser-production-fixture',
    build: {
      staticRoot,
      command: 'pnpm --dir ani-rss-ui exec vite build --outDir <unique> --emptyOutDir false'
    },
    environment: {
      os: process.platform,
      arch: process.arch,
      cpu: cpus()[0]?.model || 'unknown',
      node: process.version
    },
    expectedScenarios,
    scenarios: Object.fromEntries([...completed.entries()]
        .sort(([left], [right]) => left.localeCompare(right))),
    raw: {
      requests,
      httpErrorRequests: requests.filter(request => request.status >= 400),
      completedAt: new Date().toISOString()
    },
    limitations: [
      'The application API and authentication boundary are synthetic local fixtures; the production Vue build, browser loader, router, and UI interactions are real.',
      'Browser resource transfer bytes reflect this fixture server serving generated gzip sidecars when the browser advertises gzip; fixed gzip bytes are measured separately from the Vite manifest.',
      'No production account, subscription, downloader, external source, media, or user file is accessed.'
    ]
  }
  writeFileSync(outputPath, JSON.stringify(output, null, 2) + '\n', 'utf8')
  console.log(`W7_BROWSER_REPORT=${outputPath}`)
}

const server = createServer(async (request, response) => {
  const started = performance.now()
  const requestUrl = new URL(request.url || '/', 'http://127.0.0.1')
  let status = 404
  let bytes = 0
  let contentEncoding = 'identity'
  let mode = parseModePath(requestUrl.pathname)?.mode || null
  try {
    if (requestUrl.pathname === '/__w7/health') {
      status = 200
      bytes = sendJson(response, 200, {status: 'ok'})
    } else if (requestUrl.pathname === '/__w7/complete' && request.method === 'POST') {
      const chunks = []
      for await (const chunk of request) chunks.push(chunk)
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
      if (!expectedScenarios.includes(body.scenario)) {
        status = 400
        bytes = sendJson(response, 400, {error: 'unexpected scenario'})
      } else {
        completed.set(body.scenario, {
          ...body,
          receivedAt: new Date().toISOString()
        })
        status = 200
        bytes = sendJson(response, 200, {received: body.scenario})
        if (expectedScenarios.every(scenario => completed.has(scenario))) {
          writeReport()
          setTimeout(() => server.close(() => process.exit(0)), 50)
        }
      }
    } else {
      const parsed = parseModePath(requestUrl.pathname)
      if (!parsed) {
        status = 404
        bytes = send(response, 404, 'Not found')
      } else if (parsed.relativePath.startsWith('api/')) {
        const result = apiResponse(parsed.mode, request.method || 'GET', parsed.relativePath, requestUrl)
        if (!result) {
          status = 404
          bytes = sendJson(response, 404, {code: 'NOT_FOUND', message: 'fixture endpoint not found'})
        } else {
          status = result.status
          if (Buffer.isBuffer(result.body)) {
            bytes = send(response, result.status, result.body, {
              'Content-Type': 'video/mp4',
              ...(result.headers || {})
            })
          } else {
            bytes = sendJson(response, result.status, result.body)
          }
        }
      } else {
        const served = serveStatic(request, response, parsed.relativePath)
        status = served.status
        bytes = served.bytes
        contentEncoding = served.contentEncoding || 'identity'
      }
    }
  } catch (error) {
    status = 500
    bytes = sendJson(response, 500, {code: 'FIXTURE_ERROR', message: String(error?.message || error)})
  } finally {
    requests.push({
      method: request.method || 'GET',
      path: requestUrl.pathname,
      query: requestUrl.search,
      mode,
      status,
      contentEncoding,
      bytes,
      durationMs: Number((performance.now() - started).toFixed(3)),
      resourceKind: /\.(?:js|css)$/.test(requestUrl.pathname) ? 'js-or-css' : 'other'
    })
  }
})

server.listen(0, '127.0.0.1', () => {
  const address = server.address()
  console.log(`W7_BROWSER_BASE_URL=http://127.0.0.1:${address.port}`)
  console.log(`W7_BROWSER_EXPECTED=${expectedScenarios.join(',')}`)
})
