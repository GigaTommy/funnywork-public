import { expect, it } from 'vitest';
import { createServer } from 'vite';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { resolve } from 'node:path';

it('preserves grouped object-property arithmetic through the actual management Vite pipeline', async () => {
 const dir=await mkdtemp(resolve(process.cwd(),'.sfc-expression-'));
 const file=resolve(dir,'Probe.vue');
 await writeFile(file,'<template><div /></template><script lang="ts">export const first={offset:(1-1)*50};export const second={offset:(2-1)*50};export default {};</script>');
 let server:Awaited<ReturnType<typeof createServer>>|undefined;
 try {
  server=await createServer({configFile:resolve(process.cwd(),'vite.config.ts'),cacheDir:resolve(dir,'cache'),optimizeDeps:{noDiscovery:true,include:[],entries:[]},server:{middlewareMode:true,watch:null},appType:'custom'});
  const result=await server.ssrLoadModule(file);
  expect(result.first.offset).toBe(0);expect(result.second.offset).toBe(50);
 } finally {await server?.close();await rm(dir,{recursive:true,force:true});}
},30000);
