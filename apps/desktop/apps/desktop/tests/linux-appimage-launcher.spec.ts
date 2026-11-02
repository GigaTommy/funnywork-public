/** AppImage startup must never disable the Chromium sandbox when host namespaces are unavailable. */
import { spawn } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs'
import { rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, relative } from 'node:path'
import { expect, it, onTestFinished } from 'vitest'

it.skipIf(process.platform === 'win32')('preserves invocation without probing or weakening the host sandbox', async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'desktop-appimage-launcher-')))
  onTestFinished(async () => { await rm(root, { recursive: true, force: true }) })
  const application = join(root, 'Application 中文 with spaces')
  mkdirSync(application)
  const launcher = join(application, 'AppRun')
  copyFileSync(join(import.meta.dirname, '../cli/AppRun-linux'), launcher)
  chmodSync(launcher, 0o755)
  symlinkSync(process.execPath, join(application, 'funnywork'))
  const command = join(root, 'application-link')
  symlinkSync(relative(root, launcher), command)
  const tools = join(root, 'tools')
  mkdirSync(tools)
  const probe = join(root, 'unexpected-probe')
  writeFileSync(join(tools, 'unshare'), `#!/bin/sh\ntouch '${probe}'\nexit 1\n`, { mode: 0o755 })
  const args = ['hello world', '中文', '$HOME', '`literal`', 'quote"inside', '']
  const script = [
    'const chunks = []; for await (const chunk of process.stdin) chunks.push(chunk);',
    'console.log(JSON.stringify({ args: process.argv.slice(1), cwd: process.cwd(), input: Buffer.concat(chunks).toString() }));',
    'process.stderr.write("kept stderr\\n"); process.exitCode = 23;',
  ].join('\n')
  const child = spawn(command, ['--input-type=module', '--eval', script, '--', ...args], {
    cwd: root, env: { ...process.env, PATH: `${tools}:${process.env.PATH ?? ''}`, APPDIR: '/intentionally-unrelated' }, stdio: 'pipe',
  })
  onTestFinished(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL') })
  let stdout = ''
  let stderr = ''
  child.stdout.setEncoding('utf8').on('data', (text: string) => { stdout += text })
  child.stderr.setEncoding('utf8').on('data', (text: string) => { stderr += text })
  const closed = new Promise<number | null>((resolve, reject) => {
    child.once('error', reject)
    child.once('close', resolve)
  })
  child.stdin.end('kept input')
  expect(await closed, stderr).toBe(23)
  expect(JSON.parse(stdout)).toEqual({ args, cwd: root, input: 'kept input' })
  expect(stderr).toBe('kept stderr\n')
  expect(existsSync(probe)).toBe(false)
})
