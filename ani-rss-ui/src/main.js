import {createApp} from 'vue'
import MainView from '@/view/MainView.vue'
import {templateIcons} from '@/js/icon-registry.js'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import router from '@/router/index.js'

const app = createApp(MainView)
// 引入图标
for (const [key, component] of Object.entries(templateIcons)) {
    app.component(key, component)
}
app.use(router)
app.mount('#app')
