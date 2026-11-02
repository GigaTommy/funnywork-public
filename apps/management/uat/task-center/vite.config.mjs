import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
const root=fileURLToPath(new URL('.',import.meta.url));
export default defineConfig({root,plugins:[vue()],resolve:{alias:[{find:/^vue$/,replacement:resolve(root,'../../node_modules/vue/dist/vue.esm-bundler.js')},{find:'@/utils/funnyworker-api',replacement:resolve(root,'fixture.ts')},{find:/^@\//,replacement:resolve(root,'../../src')+'/'}]},server:{host:'127.0.0.1',port:19194,strictPort:true,fs:{allow:[resolve(root,'../../../..')]}},cacheDir:resolve(root,'../../../../.local/agent/packages/task-center-vite')});
