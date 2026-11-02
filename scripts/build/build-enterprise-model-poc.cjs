#!/usr/bin/env node
/* Rebuild the standalone offline page from the maintained shell and browser helper. */
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const core=fs.readFileSync(path.join(root,'scripts/checks/browser-model-probe.js'),'utf8');
if(/<\/script/i.test(core))throw new Error('Browser helper cannot contain a closing script tag');
const shell=fs.readFileSync(path.join(root,'designs/enterprise-model-poc/page-shell.manual.html'),'utf8');
const marker='<!-- FW_CORE_BEGIN -->\n<!-- FW_CORE_END -->';
if(!shell.includes(marker))throw new Error('Missing browser helper insertion marker');
fs.writeFileSync(path.join(root,'designs/enterprise-model-poc/index.manual.html'),shell.replace(marker,'<!-- FW_CORE_BEGIN -->\n<script id="fw-core">\n'+core+'\n</script>\n<!-- FW_CORE_END -->'));
console.log('Standalone enterprise model POC page built without external assets.');
