import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { viteSingleFile } from 'vite-plugin-singlefile'

// 电脑桌面网页版：入口 web.html（桌面布局，不做手机适配）
// 产出单个 index.html（JS/CSS 全内联），双击即可打开，无需服务器
export default defineConfig({
  plugins: [vue(), viteSingleFile()],
  base: './',
  define: { __BUILD_TIME__: JSON.stringify(new Date().toISOString().slice(0, 19).replace('T', ' ')) },
  build: {
    outDir: 'dist-web',
    rollupOptions: {
      input: { index: fileURLToPath(new URL('./web.html', import.meta.url)) },
    },
    assetsInlineLimit: 100000000,
    cssCodeSplit: false,
    chunkSizeWarningLimit: 5000,
  },
})
