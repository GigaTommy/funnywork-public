import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import react from '@vitejs/plugin-react';
import ts from 'typescript';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('.', import.meta.url));
const desktop = resolve(root, '../../../desktop');
const sourceConfig = ts.readConfigFile(resolve(desktop, 'tsconfig.base.json'), ts.sys.readFile).config;
const sourceAliases = Object.entries(sourceConfig.compilerOptions.paths).map(([name, paths]) => ({
  find: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace('\\*', '(.*)') + '$'),
  replacement: resolve(desktop, paths[0]).replace('*', '$1')
}));
export default defineConfig({
  root,
  plugins: [vue(), react()],
  resolve: {
    alias: [
      { find: /^vue$/, replacement: resolve(root, '../../node_modules/vue/dist/vue.esm-bundler.js') },
      { find: '@/utils/funnyworker-api', replacement: resolve(root, 'fixture.ts') },
      { find: /^@\//, replacement: resolve(root, '../../src') + '/' },
      { find: /^react$/, replacement: resolve(desktop, 'packages/client/ui-model-selection/node_modules/react') },
      {
        find: /^react\//,
        replacement: resolve(desktop, 'packages/client/ui-model-selection/node_modules/react') + '/'
      },
      {
        find: /^react-dom$/,
        replacement: resolve(desktop, 'packages/client/ui-model-selection/node_modules/react-dom')
      },
      {
        find: /^react-dom\//,
        replacement: resolve(desktop, 'packages/client/ui-model-selection/node_modules/react-dom') + '/'
      },
      ...sourceAliases
    ]
  },
  server: { host: '127.0.0.1', port: 19192, strictPort: true, fs: { allow: [resolve(root, '../../../..')] } },
  build: {
    rollupOptions: { input: { management: resolve(root, 'index.html'), client: resolve(root, 'client.html') } },
    outDir: resolve(root, '../../../../.local/uat/model-ui-build'),
    emptyOutDir: true
  }
});
