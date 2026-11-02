#!/usr/bin/env node
/* Package the existing native checker as one self-contained, double-clickable CMD. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const destination=path.resolve(process.argv[2]||path.join(root,'.local/releases/enterprise-model-poc-single-20261023/FunnyWork-Model-Check.cmd'));
const read=file=>fs.readFileSync(path.join(root,'scripts/checks',file),'utf8').replace(/^\uFEFF/,'').replace(/\r\n/g,'\n');
let main=read('start-model-browser-check.ps1'),engine=read('enterprise-model-browser-engine.ps1');
const browser=read('browser-model-probe.js'),texts=read('enterprise-model-browser-text.json');
for(const value of [browser,texts])assert(!/^'@\s*$/m.test(value),'Reserved PowerShell here-string terminator');
assert(engine.includes("[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'browser-model-probe.js'))"));
engine=engine.replace(/^#Requires[^\n]*\n/,'').replace("[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'browser-model-probe.js'))",'$script:EmbeddedBrowserProbe');
const embedded="$script:StandaloneRoot=$PSScriptRoot\nif($env:FW_MODEL_CHECK_SOURCE){$script:StandaloneRoot=[IO.Path]::GetDirectoryName($env:FW_MODEL_CHECK_SOURCE)}\n"+
 "$script:EmbeddedBrowserProbe=@'\n"+browser+"\n'@\n$script:EmbeddedModelText=@'\n"+texts+"\n'@\n"+engine;
const importLine=". (Join-Path $PSScriptRoot 'enterprise-model-browser-engine.ps1')";
assert(main.includes(importLine));
main=main.replace(/\$PSScriptRoot/g,'$script:StandaloneRoot').replace(importLine.replace(/\$PSScriptRoot/g,'$script:StandaloneRoot'),embedded);
main=main.replace("([IO.File]::ReadAllText((Join-Path $script:StandaloneRoot 'enterprise-model-browser-text.json')))",'$script:EmbeddedModelText');
assert(!/Join-Path[^\n]*(?:enterprise-model-browser-(?:engine|text)|browser-model-probe)/.test(main),'External source dependency remains');
const marker='###FW_POWERSHELL_PAYLOAD###';
const header=['@echo off','setlocal','set "FW_MODEL_CHECK_SOURCE=%~f0"',
 'powershell.exe -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -Command "$p=[IO.File]::ReadAllText($env:FW_MODEL_CHECK_SOURCE,[Text.Encoding]::UTF8); $m=\''+marker+'\'; $b=$p.Substring($p.LastIndexOf($m)+$m.Length); & ([ScriptBlock]::Create($b)); if($null -ne $LASTEXITCODE){exit $LASTEXITCODE}"',
 'set "FW_MODEL_CHECK_EXIT=%ERRORLEVEL%"','if not "%FW_MODEL_CHECK_EXIT%"=="0" pause','exit /b %FW_MODEL_CHECK_EXIT%',marker];
fs.mkdirSync(path.dirname(destination),{recursive:true});fs.writeFileSync(destination,header.join('\r\n')+'\r\n'+main.replace(/\n/g,'\r\n'),'utf8');
const built=fs.readFileSync(destination,'utf8');
assert.equal(built.lastIndexOf(marker),built.indexOf('\r\n'+marker)+2);
assert(built.includes('$script:EmbeddedBrowserProbe'));
assert(built.includes('$script:TextMap=Read-ModelJson $script:EmbeddedModelText'));
assert(built.includes("'browser-profile'"));
console.log(destination);console.log('Single file built: embedded controller, browser helper, UI and translations.');
