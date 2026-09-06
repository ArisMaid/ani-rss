import {existsSync, readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {gzipSync} from 'node:zlib'

const root = resolve(import.meta.dirname, '..')
const output = resolve(root, '.verify-dist')
const manifestPath = resolve(output, '.vite', 'manifest.json')
const budgetPath = resolve(root, 'scripts', 'bundle-budget.json')

if (!existsSync(manifestPath)) {
  throw new Error('bundle manifest is missing; run pnpm build:verify first')
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const budget = JSON.parse(readFileSync(budgetPath, 'utf8'))
const entries = Object.entries(manifest).filter(([, item]) => item.isEntry && item.file)

const collect = entryKey => {
  const visitedKeys = new Set()
  const assets = new Set()
  const visit = key => {
    if (!key || visitedKeys.has(key)) return
    const item = manifest[key]
    if (!item) return
    visitedKeys.add(key)
    assets.add(item.file)
    for (const css of item.css || []) assets.add(css)
    for (const imported of item.imports || []) visit(imported)
  }
  visit(entryKey)
  return [...assets]
}

const measure = (entryKey, files) => {
  const rows = files.map(file => {
    const path = resolve(output, file)
    if (!existsSync(path)) throw new Error('manifest asset is missing: ' + file)
    const content = readFileSync(path)
    return {
      file,
      type: file.endsWith('.css') ? 'css' : 'js',
      gzipBytes: gzipSync(content).byteLength,
      forbidden: /artplayer|markdown-it|PlayStartView|BackupView/i.test(file)
          || /artplayer|markdown-it|PlayStartView|BackupView/i.test(content.toString('utf8'))
    }
  })
  return {
    files: rows,
    jsGzipBytes: rows.filter(row => row.type === 'js')
        .reduce((sum, row) => sum + row.gzipBytes, 0),
    cssGzipBytes: rows.filter(row => row.type === 'css')
        .reduce((sum, row) => sum + row.gzipBytes, 0),
    gzipBytes: rows.reduce((sum, row) => sum + row.gzipBytes, 0)
  }
}

const measured = {}
const failures = []
for (const [key] of entries) {
  const result = measure(key, collect(key))
  measured[key] = result
  const forbidden = result.files.filter(row => row.forbidden)
  if (key === 'index.html') {
    if (forbidden.length) {
      failures.push('forbidden first-entry assets: ' + forbidden.map(row => row.file).join(', '))
    }
    const entryBudget = budget.entries?.[key]
    if (entryBudget?.maxJsGzipBytes !== undefined
        && result.jsGzipBytes > entryBudget.maxJsGzipBytes) {
      failures.push(`${key} JS gzip ${result.jsGzipBytes} exceeds ${entryBudget.maxJsGzipBytes}`)
    }
  }
}

const report = {
  baselineCommit: budget.baselineCommit,
  targetReduction: budget.targetReduction,
  entries: measured
}
console.log(JSON.stringify(report, null, 2))
if (failures.length) throw new Error(failures.join('; '))
