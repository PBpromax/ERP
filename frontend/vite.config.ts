import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })]
    })
  ],
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.OMS_DEV_API_ORIGIN || 'http://127.0.0.1:8081',
        changeOrigin: true
      }
    }
  }
})
