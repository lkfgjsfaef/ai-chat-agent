import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return
          }
          if (id.includes('element-plus')) {
            return 'element-plus'
          }
          if (id.includes('@element-plus/icons-vue')) {
            return 'element-icons'
          }
          if (id.includes('marked') || id.includes('highlight.js')) {
            return 'markdown-renderer'
          }
          return 'vendor'
        }
      }
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: ['.trycloudflare.com', '.ngrok-free.app', '.cpolar.top', '.cpolar.cn', '.cpolar.io'],
    proxy: {
      '/user': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/session': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/chat': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/message': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/model': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/upload': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
