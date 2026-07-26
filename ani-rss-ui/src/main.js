import {createApp} from 'vue'
import Main from './Main.vue'
import {
    ArrowDownBold, ArrowUpBold, Back, Check, Close, Delete, DocumentAdd,
    DocumentChecked, DocumentCopy, Download, Edit, EditPen, FolderAdd, Grid,
    Link, Menu, MoreFilled, Odometer, Plus, Refresh, RefreshLeft, RefreshRight,
    Remove, Right, Search, Select, SwitchButton, Tickets, Top, Upload, VideoPlay
} from '@element-plus/icons-vue'
import 'element-plus/theme-chalk/dark/css-vars.css'

const app = createApp(Main)
const icons = {
    ArrowDownBold, ArrowUpBold, Back, Check, Close, Delete, DocumentAdd,
    DocumentChecked, DocumentCopy, Download, Edit, EditPen, FolderAdd, Grid,
    Link, Menu, MoreFilled, Odometer, Plus, Refresh, RefreshLeft, RefreshRight,
    Remove, Right, Search, Select, SwitchButton, Tickets, Top, Upload, VideoPlay
}
for (const [key, component] of Object.entries(icons)) {
    app.component(key, component)
}
app.mount('#app')
