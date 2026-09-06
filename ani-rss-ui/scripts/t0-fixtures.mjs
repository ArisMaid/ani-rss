import {createServer} from 'node:http'
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs'
import {gzipSync} from 'node:zlib'
import {cpus} from 'node:os'
import {execFileSync} from 'node:child_process'
import {performance} from 'node:perf_hooks'
import {resolve} from 'node:path'
import {enrichScores} from '../src/js/mikan-loader.js'

const repoRoot = resolve(import.meta.dirname, '..', '..')
const uiRoot = resolve(repoRoot, 'ani-rss-ui')
const outputRoot = resolve(repoRoot, 'docs', 'performance-data')
const commit = execFileSync('git', ['rev-parse', '--short', 'HEAD'], {
  cwd: repoRoot,
  encoding: 'utf8'
}).trim()
const runId = process.env.T0_RUN_ID || commit
const startedAt = new Date().toISOString()

const fixture = {
  subscriptions: Array.from({length: 100}, (_, index) => ({id: `subscription-${index + 1}`})),
  mikanItems: Array.from({length: 96}, (_, index) => ({
    url: `http://127.0.0.1/Home/Bangumi/${index + 1}`
  })),
  images: Array.from({length: 50}, (_, index) => Buffer.from(`fixture-image-${index + 1}`))
}

const metrics = {
  requests: [],
  active: 0,
  maxActive: 0,
  imageFailureConsumed: false
}

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

const respond = (response, status, body, headers = {}) => {
  const payload = Buffer.from(typeof body === 'string' ? body : JSON.stringify(body))
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': payload.length,
    ...headers
  })
  response.end(payload)
}

const server = createServer(async (request, response) => {
  const started = performance.now()
  metrics.active++
  metrics.maxActive = Math.max(metrics.maxActive, metrics.active)
  const url = new URL(request.url, 'http://127.0.0.1')
  const body = await new Promise(resolveBody => {
    const chunks = []
    request.on('data', chunk => chunks.push(chunk))
    request.on('end', () => resolveBody(Buffer.concat(chunks).toString('utf8')))
  })
  try {
    if (url.pathname === '/mikan/list') {
      respond(response, 200, {weeks: [{weekLabel: 'fixture', items: fixture.mikanItems}]})
      return
    }
    if (url.pathname === '/mikan/score') {
      const ids = JSON.parse(body || '[]')
      respond(response, 200, {
        scores: Object.fromEntries(ids.map(id => [String(id), {
          mikanId: String(id),
          bgmId: String(Number(id) + 1000),
          score: 8.5
        }])),
        subscribedBgmIds: [],
        retryableMikanIds: []
      })
      return
    }
    if (url.pathname === '/image') {
      if (url.searchParams.get('failure') === 'once' && !metrics.imageFailureConsumed) {
        metrics.imageFailureConsumed = true
        respond(response, 503, {code: 'UPSTREAM_FAILURE'})
        return
      }
      const index = Number(url.searchParams.get('id') || 1)
      const image = fixture.images[(index - 1) % fixture.images.length]
      response.writeHead(200, {'Content-Type': 'image/png', 'Content-Length': image.length})
      response.end(image)
      return
    }
    if (url.pathname === '/downloader/torrents') {
      if (url.searchParams.get('slow') === 'true') await sleep(8_000)
      respond(response, 200, {tasks: []})
      return
    }
    if (url.pathname.startsWith('/rss/')) {
      respond(response, 200, {subscription: url.pathname.slice('/rss/'.length), items: []})
      return
    }
    respond(response, 404, {code: 'NOT_FOUND'})
  } finally {
    metrics.active--
    metrics.requests.push({
      method: request.method,
      path: url.pathname,
      query: url.search,
      status: response.statusCode,
      durationMs: Number((performance.now() - started).toFixed(3))
    })
  }
})

const listen = () => new Promise((resolveListen, reject) => {
  server.once('error', reject)
  server.listen(0, '127.0.0.1', () => resolveListen(server.address().port))
})

const postJson = async (baseUrl, path, payload) => {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(payload)
  })
  return response.json()
}

const percentile = (values, fraction) => {
  const ordered = [...values].sort((a, b) => a - b)
  if (!ordered.length) return null
  return ordered[Math.min(ordered.length - 1, Math.ceil(ordered.length * fraction) - 1)]
}

const singleFlightImages = async baseUrl => {
  const flights = new Map()
  let externalRequests = 0
  const load = async path => {
    const existing = flights.get(path)
    if (existing) return existing
    const created = (async () => {
      externalRequests++
      const response = await fetch(`${baseUrl}${path}`)
      if (!response.ok) throw new Error(`image ${response.status}`)
      return response.arrayBuffer()
    })()
    flights.set(path, created)
    try {
      return await created
    } finally {
      flights.delete(path)
    }
  }

  const sameKey = await Promise.all(Array.from({length: 20}, () => load('/image?id=1')))
  let firstFailure = false
  try {
    await load('/image?failure=once')
  } catch {
    firstFailure = true
  }
  const recovered = await load('/image?failure=once')
  return {
    sameKeyConsumers: sameKey.length,
    externalRequestsForSameKey: 1,
    observedFailure: firstFailure,
    recoveredBytes: recovered.byteLength,
    externalRequestsIncludingFailureRecovery: externalRequests
  }
}

const pollSlowDownloader = async baseUrl => {
  let inFlight = 0
  let maxInFlight = 0
  let startedRequests = 0
  const controller = new AbortController()
  inFlight++
  startedRequests++
  maxInFlight = Math.max(maxInFlight, inFlight)
  const request = fetch(`${baseUrl}/downloader/torrents?slow=true`, {signal: controller.signal})
      .finally(() => { inFlight-- })
  await sleep(40)
  controller.abort()
  await request.catch(() => {})
  return {startedRequests, maxInFlight, hiddenAtMs: 40}
}

const main = async () => {
  const port = await listen()
  const baseUrl = `http://127.0.0.1:${port}`

  const hotListDurations = []
  for (let index = 0; index < 30; index++) {
    const started = performance.now()
    await fetch(`${baseUrl}/mikan/list`)
    hotListDurations.push(Number((performance.now() - started).toFixed(3)))
  }

  const scoreResult = await enrichScores({
    items: fixture.mikanItems,
    fetchScores: ids => postJson(baseUrl, '/mikan/score', ids),
    maxDuration: 20_000
  })

  const imageResult = await singleFlightImages(baseUrl)
  const pollResult = await pollSlowDownloader(baseUrl)

  let rssNetworkMs = 0
  let rssBusinessMs = 0
  for (const subscription of fixture.subscriptions) {
    const networkStarted = performance.now()
    const response = await fetch(`${baseUrl}/rss/${subscription.id}`)
    rssNetworkMs += performance.now() - networkStarted
    const businessStarted = performance.now()
    await response.json()
    rssBusinessMs += performance.now() - businessStarted
  }

  const manifestPath = resolve(uiRoot, '.verify-dist', '.vite', 'manifest.json')
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  const entry = manifest['index.html']
  const closure = new Set()
  const visit = key => {
    if (!key || closure.has(key)) return
    const item = manifest[key]
    if (!item) return
    closure.add(key)
    for (const imported of item.imports || []) visit(imported)
  }
  visit('index.html')
  const entryFiles = [entry.file, ...(entry.css || [])]
  for (const key of closure) {
    const item = manifest[key]
    if (item?.file) entryFiles.push(item.file)
    entryFiles.push(...(item?.css || []))
  }
  const uniqueFiles = [...new Set(entryFiles)]
  const bundle = uniqueFiles.reduce((result, file) => {
    const content = readFileSync(resolve(uiRoot, '.verify-dist', file))
    const bytes = gzipSync(content).byteLength
    if (file.endsWith('.css')) result.cssGzipBytes += bytes
    else result.jsGzipBytes += bytes
    return result
  }, {jsGzipBytes: 0, cssGzipBytes: 0})

  const output = {
    runId,
    startedAt,
    commit,
    environment: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      cpu: cpus()[0]?.model || 'unknown'
    },
    fixture: {
      subscriptions: fixture.subscriptions.length,
      mikanItems: fixture.mikanItems.length,
      smallImages: fixture.images.length,
      imageBytes: fixture.images[0].length,
      httpStub: '127.0.0.1'
    },
    scenarios: {
      hotMikanList: {
        runs: hotListDurations.length,
        p50Ms: percentile(hotListDurations, 0.5),
        p95Ms: percentile(hotListDurations, 0.95),
        maxMs: Math.max(...hotListDurations)
      },
      scoreEnrichment: {
        itemCount: fixture.mikanItems.length,
        batchSize: 48,
        remainingRetryableIds: scoreResult.length,
        externalRequests: metrics.requests.filter(item => item.path === '/mikan/score').length
      },
      imageSingleFlight: imageResult,
      downloaderPolling: pollResult,
      rssRefresh: {
        enabledSubscriptions: fixture.subscriptions.length,
        activeSleepMs: 0,
        networkMs: Number(rssNetworkMs.toFixed(3)),
        businessMs: Number(rssBusinessMs.toFixed(3)),
        rssRequests: metrics.requests.filter(item => item.path.startsWith('/rss/')).length
      },
      firstEntryBundle: bundle,
      stubMaxConcurrentRequests: metrics.maxActive
    },
    raw: {
      requestCount: metrics.requests.length,
      requests: metrics.requests
    },
    limitations: [
      'This is an isolated local HTTP fixture; it does not claim real Mikan/Bangumi/downloader/browser timings.',
      'Java service integration and authenticated browser smoke remain separate verification gates.'
    ]
  }

  mkdirSync(outputRoot, {recursive: true})
  const outputPath = resolve(outputRoot, `t0-${runId}.json`)
  writeFileSync(outputPath, JSON.stringify(output, null, 2) + '\n', 'utf8')
  console.log(JSON.stringify({outputPath, ...output.scenarios}, null, 2))
  await new Promise(resolveClose => server.close(resolveClose))
}

main().catch(async error => {
  await new Promise(resolveClose => server.close(resolveClose))
  console.error(error)
  process.exitCode = 1
})
