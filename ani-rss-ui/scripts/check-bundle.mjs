import {existsSync, readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {gzipSync} from 'node:zlib'

const root = resolve(import.meta.dirname, '..')
const output = resolve(root, '.verify-dist')
const manifestPath = resolve(output, '.vite', 'manifest.json')

if (!existsSync(manifestPath)) {
  throw new Error('bundle manifest is missing; run pnpm build:verify first')
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const entries = Object.values(manifest).filter(item => item.isEntry && item.file)
const visited = new Set()
const assets = new Set()

const visit = item => {
  if (!item || visited.has(item)) return
  visited.add(item)
  assets.add(item)
  for (const child of Object.values(manifest)) {
    if (child.file === item) {
      for (const imported of child.imports || []) visit(manifest[imported]?.file)
      for (const css of child.css || []) assets.add(css)
    }
  }
}

for (const entry of entries) visit(entry.file)

const rows = [...assets].map(file => {
  const path = resolve(output, file)
  if (!existsSync(path)) throw new Error('manifest asset is missing: ' + file)
  return {file, gzipBytes: gzipSync(readFileSync(path)).byteLength}
})

const forbidden = rows.filter(row => /artplayer|markdown-it|PlayStartView|BackupView/i.test(row.file))
if (forbidden.length) {
  throw new Error('forbidden first-entry assets detected: ' + forbidden.map(row => row.file).join(', '))
}

const total = rows.reduce((sum, row) => sum + row.gzipBytes, 0)
console.log(JSON.stringify({
  entryCount: entries.length,
  assetCount: rows.length,
  gzipBytes: total,
  assets: rows.sort((a, b) => b.gzipBytes - a.gzipBytes)
}, null, 2))
