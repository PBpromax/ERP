import { createApp } from 'vue'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-message.css'
import './styles/global.css'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
