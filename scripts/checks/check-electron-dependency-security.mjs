#!/usr/bin/env node
/** Qualify the desktop's Electron builtin access and manifest invalidation without credentials. */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const executable = process.argv[2];
assert(executable && isAbsolute(executable), 'Pass the absolute Electron executable path');
const temporary = mkdtempSync(join(tmpdir(), 'electron-dependency-security-'));
try {
  const result = spawnSync(executable, ['--expose-internals', '--input-type=module', '-e', `
    import assert from 'node:assert/strict';
    import { createRequire } from 'node:module';
    import { writeFileSync, existsSync } from 'node:fs';
    import { join } from 'node:path';
    import { pathToFileURL } from 'node:url';
    const [root, temporary] = process.argv.slice(1);
    const require = createRequire(join(root, 'apps/desktop/packages/boot/app-boot/package.json'));
    const addon = require('node-addon-require-builtin');
    for (const name of ['internal/modules/esm/loader', 'internal/modules/cjs/loader', 'internal/modules/helpers',
      'internal/modules/esm/utils', 'internal/modules/esm/resolve', 'internal/modules/package_json_reader']) {
      assert(addon.requireBuiltin(name));
    }
    const marker = join(temporary, 'unexpected.txt'), file = join(temporary, 'foreign.cjs');
    writeFileSync(file, 'require("node:fs").writeFileSync('+JSON.stringify(marker)+', "executed")');
    assert.throws(() => addon.requireBuiltin(file));
    assert(!existsSync(marker));
    const { PackageManifests } = await import(pathToFileURL(join(root, 'apps/desktop/packages/boot/hmr/src/package-manifest.ts')));
    const manifests = new PackageManifests();
    try {
      const manifest = join(temporary, 'package.json'), entry = pathToFileURL(join(temporary, 'entry.js')).href;
      const reader = addon.requireBuiltin('internal/modules/package_json_reader');
      writeFileSync(manifest, JSON.stringify({ type: 'commonjs' }));
      assert.equal(reader.getPackageScopeConfig(entry).type, 'commonjs');
      writeFileSync(manifest, JSON.stringify({ type: 'module' }));
      manifests.invalidate(manifest);
      assert.equal(reader.getPackageScopeConfig(entry).type, 'module');
    } finally { manifests.dispose(); }
    console.log(JSON.stringify({electron:process.versions.electron,node:process.versions.node,
      builtinAccess:true,foreignModuleRejected:true,manifestInvalidation:true}));
  `, root, temporary], {
    encoding: 'utf8', timeout: 45000,
    env: { ...process.env, ELECTRON_RUN_AS_NODE: '1', NARB_NATIVE_CACHE_DIR: join(temporary, 'native-cache') },
  });
  process.stdout.write(result.stdout || '');
  process.stderr.write(result.stderr || '');
  assert.ifError(result.error);
  assert.equal(result.signal, null);
  assert.equal(result.status, 0, 'Electron dependency qualification failed');
} finally { rmSync(temporary, { recursive: true, force: true }); }
