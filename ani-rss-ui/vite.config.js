import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'
import compression from 'vite-plugin-compression'

let serverHost = process.env['SERVER_HOST'];

let dirname = import.meta.dirname;
const isVitest = process.env.VITEST === 'true' || process.env.NODE_ENV === 'test'

const normalizeModuleId = moduleId => {
    const normalized = moduleId.replaceAll('\\', '/')
    const projectRoot = path.resolve(dirname).replaceAll('\\', '/')
    if (normalized.startsWith(`${projectRoot}/`)) {
        return normalized.slice(projectRoot.length + 1)
    }
    return normalized
}

const bundleModuleMap = () => ({
    name: 'bundle-module-map',
    apply: 'build',
    generateBundle(_options, bundle) {
        const chunks = {}
        for (const [fileName, output] of Object.entries(bundle)) {
            if (output.type !== 'chunk') continue
            chunks[fileName] = {
                modules: Object.keys(output.modules).map(normalizeModuleId).sort()
            }
        }
        this.emitFile({
            type: 'asset',
            fileName: '.vite/module-chunks.json',
            source: JSON.stringify({schemaVersion: 1, chunks}, null, 2) + '\n'
        })
    }
})

export default defineConfig({
    base: './',
    server: {
        port: 37789,
        proxy: {
            '/api': {
                target: serverHost ? serverHost : 'http://127.0.0.1:7789',
                changeOrigin: true,
                secure: false
            }
        }
    },
    plugins: [
        vue(),
        bundleModuleMap(),
        !isVitest && AutoImport({
            imports: ['vue'],
            resolvers: [ElementPlusResolver()]
        }),
        !isVitest && Components({
            resolvers: [ElementPlusResolver({
                importStyle: 'css',
            })]
        }),
        compression({
            // 输出压缩日志
            verbose: true,
            // 是否禁用压缩
            disable: false,
            // 对超过10KB的文件进行压缩
            threshold: 10240,
            // 使用gzip压缩
            algorithm: 'gzip',
            // 压缩后文件的扩展名
            ext: '.gz'
        }),
    ].filter(Boolean),
    resolve: {
        alias: {
            '@': path.resolve(dirname, './src/')
        }
    },
    build: {
        manifest: true,
        chunkSizeWarningLimit: 1024,
        rollupOptions: {
            input: {
                main: path.resolve(dirname, 'index.html'),
                bgmOauthCallback: path.resolve(dirname, 'bgm-oauth-callback.html')
            },
            output: {
                // Keep Rollup's default graph splitting. Forced Element Plus chunks
                // create a circular initialization path with the wildcard icon registry.
                chunkFileNames: () => {
                    return `assets/[name]-[hash].js`;
                }
            }
        },
        minify: 'terser',
        terserOptions: {
            compress: {
                drop_console: false,
                drop_debugger: true,
            }
        }
    }
})
