import {readdir, readFile, writeFile} from 'node:fs/promises'
import {extname, join} from 'node:path'
import {promisify} from 'node:util'
import {gzip} from 'node:zlib'
import {fileURLToPath} from 'node:url'

const gzipAsync = promisify(gzip)
const root = fileURLToPath(new URL('../dist/', import.meta.url))
const compressible = new Set(['.css', '.html', '.js', '.svg'])
const threshold = 10 * 1024

async function files(directory) {
  const result = []
  for (const entry of await readdir(directory, {withFileTypes: true})) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) result.push(...await files(path))
    else if (entry.isFile() && !entry.name.endsWith('.gz')) result.push(path)
  }
  return result
}

let compressed = 0
for (const path of await files(root)) {
  if (!compressible.has(extname(path).toLowerCase())) continue
  const source = await readFile(path)
  if (source.byteLength < threshold) continue
  await writeFile(`${path}.gz`, await gzipAsync(source, {level: 9}))
  compressed += 1
}

console.log(`Generated ${compressed} gzip assets beside their source files.`)
