import {existsSync, readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {gzipSync} from 'node:zlib'

const root = resolve(import.meta.dirname, '..')
const outputArgument = process.env.BUNDLE_OUTPUT_DIR || process.argv[2] || '.verify-dist'
const output = resolve(root, outputArgument)
const manifestPath = resolve(output, '.vite', 'manifest.json')
const moduleMapPath = resolve(output, '.vite', 'module-chunks.json')
const budgetPath = resolve(root, 'scripts', 'bundle-budget.json')

if (!existsSync(manifestPath)) {
  throw new Error(`bundle manifest is missing: ${manifestPath}`)
}
if (!existsSync(moduleMapPath)) {
  throw new Error(`bundle module map is missing: ${moduleMapPath}`)
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const moduleMap = JSON.parse(readFileSync(moduleMapPath, 'utf8'))
const budget = JSON.parse(readFileSync(budgetPath, 'utf8'))

const scenarios = {
  // The hash router resolves /home before authentication finishes, so the
  // default route chunk is an actual login-screen startup dependency.
  login: ['src/view/LoginView.vue', 'src/view/home/DashboardView.vue'],
  home: ['src/view/home/MainLayoutView.vue', 'src/view/home/DashboardView.vue'],
  subscriptions: ['src/view/home/MainLayoutView.vue', 'src/view/home/SubscriptionView.vue']
}

const requireManifestItem = key => {
  const item = manifest[key]
  if (!item) throw new Error(`manifest entry is missing: ${key}`)
  return item
}

const collect = entryKey => {
  const visitedKeys = new Set()
  const assets = new Set()
  const visit = key => {
    if (!key || visitedKeys.has(key)) return
    const item = requireManifestItem(key)
    visitedKeys.add(key)
    if (item.file) assets.add(item.file)
    for (const css of item.css || []) assets.add(css)
    for (const imported of item.imports || []) visit(imported)
  }
  visit(entryKey)
  return {keys: visitedKeys, assets}
}

const staticEntryClosure = collect('index.html')

const scenarioClosures = Object.fromEntries(Object.entries(scenarios).map(([name, roots]) => {
  const keys = new Set(staticEntryClosure.keys)
  const assets = new Set(staticEntryClosure.assets)
  for (const rootKey of roots) {
    const closure = collect(rootKey)
    for (const key of closure.keys) keys.add(key)
    for (const asset of closure.assets) assets.add(asset)
  }
  return [name, {keys, assets}]
}))

const moduleRowsByChunk = new Map(Object.entries(moduleMap.chunks || {})
    .map(([file, value]) => [file, value.modules || []]))

const classifyModule = moduleId => {
  if (/node_modules\/(?:artplayer|artplayer-plugin-multiple-subtitles)(?:\/|$)/i.test(moduleId)) {
    return 'player-library'
  }
  if (/node_modules\/markdown-it(?:\/|$)/i.test(moduleId)
      || /node_modules\/markdown-it-github-alerts(?:\/|$)/i.test(moduleId)) {
    return 'markdown-library'
  }
  if (/src\/view\/play\/(?:PlayListView|PlayStartView|ArtplayerView)\.vue$/i.test(moduleId)) {
    return 'player-view'
  }
  if (/src\/view\/config\/basic\/BackupView\.vue$/i.test(moduleId)) {
    return 'backup-view'
  }
  return null
}

const forbiddenModulesIn = assets => [...moduleRowsByChunk.entries()]
    .filter(([file]) => assets.has(file))
    .flatMap(([file, modules]) => modules
      .map(moduleId => ({file, moduleId, category: classifyModule(moduleId)}))
      .filter(row => row.category))

const measure = assets => {
  const files = [...assets].sort().map(file => {
    const assetPath = resolve(output, file)
    if (!existsSync(assetPath)) throw new Error(`manifest asset is missing: ${file}`)
    const content = readFileSync(assetPath)
    return {
      file,
      type: file.endsWith('.css') ? 'css' : 'js',
      gzipBytes: gzipSync(content).byteLength
    }
  })
  const jsGzipBytes = files.filter(row => row.type === 'js')
      .reduce((sum, row) => sum + row.gzipBytes, 0)
  const cssGzipBytes = files.filter(row => row.type === 'css')
      .reduce((sum, row) => sum + row.gzipBytes, 0)
  return {
    files,
    jsGzipBytes,
    cssGzipBytes,
    gzipBytes: jsGzipBytes + cssGzipBytes
  }
}

const failures = []
const staticMeasurement = measure(staticEntryClosure.assets)
const staticLimits = budget.staticEntryClosure
if (!staticLimits) {
  failures.push('missing budget for staticEntryClosure')
} else if (staticLimits.maxJsGzipBytes !== undefined
      && staticMeasurement.jsGzipBytes > staticLimits.maxJsGzipBytes) {
  failures.push(`staticEntryClosure JS gzip ${staticMeasurement.jsGzipBytes} exceeds ${staticLimits.maxJsGzipBytes}`)
} else if (staticLimits.maxCssGzipBytes !== undefined
      && staticMeasurement.cssGzipBytes > staticLimits.maxCssGzipBytes) {
  failures.push(`staticEntryClosure CSS gzip ${staticMeasurement.cssGzipBytes} exceeds ${staticLimits.maxCssGzipBytes}`)
}
const scenarioMeasurements = {}
for (const [name, closure] of Object.entries(scenarioClosures)) {
  const measurement = measure(closure.assets)
  const forbiddenModules = forbiddenModulesIn(closure.assets)
  const limits = budget.scenarios?.[name] || budget.entries?.[name]
  if (!limits) {
    failures.push(`missing budget for scenario: ${name}`)
  } else if (limits.maxJsGzipBytes !== undefined
      && measurement.jsGzipBytes > limits.maxJsGzipBytes) {
    failures.push(`${name} JS gzip ${measurement.jsGzipBytes} exceeds ${limits.maxJsGzipBytes}`)
  }
  if (limits && limits.maxCssGzipBytes !== undefined
      && measurement.cssGzipBytes > limits.maxCssGzipBytes) {
    failures.push(`${name} CSS gzip ${measurement.cssGzipBytes} exceeds ${limits.maxCssGzipBytes}`)
  }
  if (forbiddenModules.length) {
    failures.push(`${name} includes forbidden modules: ${forbiddenModules
      .map(row => `${row.category}:${row.moduleId}`).join(', ')}`)
  }
  scenarioMeasurements[name] = {
    roots: scenarios[name],
    manifestKeys: [...closure.keys].sort(),
    measurement,
    forbiddenModules
  }
}

const report = {
  schemaVersion: 2,
  output,
  staticEntryClosure: {
    entry: 'index.html',
    manifestKeys: [...staticEntryClosure.keys].sort(),
    measurement: staticMeasurement
  },
  scenarios: scenarioMeasurements,
  moduleMap: {
    path: moduleMapPath,
    schemaVersion: moduleMap.schemaVersion,
    chunkCount: moduleRowsByChunk.size
  }
}
console.log(JSON.stringify(report, null, 2))
if (failures.length) throw new Error(failures.join('; '))
