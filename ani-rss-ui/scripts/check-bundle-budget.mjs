import {readFile, stat} from 'node:fs/promises'
import {join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {promisify} from 'node:util'
import {gzip} from 'node:zlib'

const gzipAsync = promisify(gzip)

const distUrl = new URL('../dist/', import.meta.url)
const dist = fileURLToPath(distUrl)
const manifest = JSON.parse(await readFile(new URL('.vite/manifest.json', distUrl), 'utf8'))
const entry = Object.values(manifest).find(chunk =>
  chunk.isEntry && (chunk.name === 'main' || chunk.src === 'index.html'))
if (!entry) throw new Error('Unable to locate the main entry in the Vite manifest.')

const chunksByFile = new Map(Object.values(manifest).map(chunk => [chunk.file, chunk]))
const initialFiles = new Set()
const visit = chunk => {
  if (!chunk || initialFiles.has(chunk.file)) return
  initialFiles.add(chunk.file)
  for (const imported of chunk.imports || []) visit(manifest[imported] || chunksByFile.get(imported))
}
visit(entry)

const jsFiles = [...initialFiles].filter(file => file.endsWith('.js'))
const sizes = []
for (const file of jsFiles) {
  const gzipPath = join(dist, `${file}.gz`)
  let bytes
  try {
    bytes = (await stat(gzipPath)).size
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
    bytes = (await gzipAsync(await readFile(join(dist, file)), {level: 9})).byteLength
  }
  sizes.push({file, bytes})
}

const total = sizes.reduce((sum, item) => sum + item.bytes, 0)
const baseline = 690 * 1024
const totalLimit = Math.floor(baseline * 0.70)
const chunkLimit = 250 * 1024
const kib = bytes => (bytes / 1024).toFixed(1)

for (const item of sizes) {
  if (item.bytes > chunkLimit) {
    throw new Error(`Initial chunk ${item.file} is ${kib(item.bytes)} KiB gzip; limit is 250 KiB.`)
  }
}
if (total > totalLimit) {
  throw new Error(`Initial JS is ${kib(total)} KiB gzip; 30% reduction budget is ${kib(totalLimit)} KiB.`)
}

console.log(`Initial JS: ${kib(total)} KiB gzip across ${sizes.length} chunks (budget ${kib(totalLimit)} KiB).`)
