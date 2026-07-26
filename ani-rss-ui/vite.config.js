import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath, URL} from 'node:url'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'

let serverHost = process.env['SERVER_HOST'];

export default defineConfig({
    base: './',
    server: {
        port: 37789,
        proxy: {
            '/api': {
                target: serverHost ? serverHost : 'http://127.0.0.1:7789',
                changeOrigin: false
            }
        }
    },
    plugins: [
        vue(),
        AutoImport({
            imports: ['vue'],
            resolvers: [ElementPlusResolver()]
        }),
        Components({
            resolvers: [ElementPlusResolver({
                importStyle: 'css',
            })]
        }),
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src/', import.meta.url))
        }
    },
    build: {
        manifest: true,
        rollupOptions: {
            input: {
                main: fileURLToPath(new URL('./index.html', import.meta.url)),
                bgmOauthCallback: fileURLToPath(new URL('./bgm-oauth-callback.html', import.meta.url))
            },
            output: {
                codeSplitting: {
                    groups: [
                        {
                            name: 'vue',
                            test: /node_modules[\\/](vue|@vueuse[\\/]core|@vicons[\\/]fa)/,
                        },
                        {
                            name: 'utils',
                            test: /node_modules[\\/](markdown-it|markdown-it-github-alerts)/,
                        },
                        {
                            name: 'element-icon',
                            test: /node_modules[\\/](@element-plus[\\/]icons-vue)/,
                        },
                        {
                            name: 'artplayer',
                            test: /node_modules[\\/](artplayer|artplayer-plugin-multiple-subtitles)/,
                        },
                        {
                            name: 'shiki',
                            test: /node_modules[\\/]shiki/,
                        },
                        {
                            name: 'element-plus',
                            test: /node_modules[\\/]element-plus/
                        }
                    ]
                },
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
