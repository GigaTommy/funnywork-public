import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';
export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: ['react', 'react-dom'], alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: { include: ['src/**/__tests__/*.test.ts'], environment: 'node' }
});
