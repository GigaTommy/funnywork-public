import { defineConfig } from 'vite'
export default defineConfig({
 base: '/admin/',
 build: { outDir: '../../services/business/src/main/resources/static/admin', emptyOutDir: true, assetsDir: '', rollupOptions: { output: { entryFileNames: 'app.js', assetFileNames: asset => asset.name?.endsWith('.css') ? 'app.css' : '[name][extname]' } } },
 server: { proxy: { '/api': 'http://127.0.0.1:8765' } },
 esbuild: { jsx: 'automatic' },
})
