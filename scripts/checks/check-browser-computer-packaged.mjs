#!/usr/bin/env node
/**
 * Run the packaged Desktop Host and its normal AgentLoop against local synthetic fixtures only.
 * Usage: node scripts/checks/check-browser-computer-packaged.mjs --app /absolute/FunnyWork.app
 *   --home /absolute/checkout/.local/agent/tmp/new-profile --report /absolute/external/report.json
 *   [--fixture-app /absolute/task-owned/FunnyWorkAXFixture.app] [--gui] [--headless]
 * --home must not exist. Success removes it; failure preserves it. HOME is never overridden.
 * --gui-only diagnoses the login gate without running the model or AppKit actions.
 * Set TMPDIR, TMP and TEMP to a short directory on /Volumes/SSD before running.
 */
import { spawn, execFileSync } from 'node:child_process'
import { constants, existsSync, readFileSync } from 'node:fs'
import { access, mkdir, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { basename, dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const project = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const capabilityBundle = '@deepseek-ai/dsh-funnywork-desktop-capabilities'
const pluginName = 'funnywork-packaged-capability-smoke'
const sentinel = 'PACKAGED_BROWSER_COMPUTER_OK'
const chinese = '打包版本中文输入已验证'
const marker = 'FUNNYWORK_PACKAGED_SMOKE_JSON:'
const browserTools = ['browser_navigate', 'browser_snapshot', 'browser_fill_form', 'browser_click']
  .map(name => `mcp__playwright-mcp__${name}`)

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function options(args) {
  const result = {}
  for (let i = 0; i < args.length; i++) {
    const key = args[i]
    assert(['--app', '--home', '--report', '--browser', '--fixture-app', '--headless', '--gui', '--gui-only', '--self-check'].includes(key), `Unknown argument: ${key}`)
    if (['--headless', '--gui', '--gui-only', '--self-check'].includes(key)) result[key.slice(2)] = true
    else {
      assert(args[i + 1] !== undefined && !args[i + 1].startsWith('--'), `Missing value: ${key}`)
      result[key.slice(2)] = args[++i]
    }
  }
  return result
}

/** Preserve HOME; allow only ordinary OS/runtime names, never inherited model or credential variables. */
function isolatedEnvironment(home, executable, runtimeBin) {
  const allowed = ['HOME', 'USER', 'LOGNAME', 'PATH', 'SHELL', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ',
    'TMPDIR', 'TMP', 'TEMP', 'PWTEST_SOCKETS_DIR', '__CF_USER_TEXT_ENCODING']
  const env = Object.fromEntries(allowed.flatMap(name => process.env[name] === undefined ? [] : [[name, process.env[name]]]))
  return {
    ...env, ELECTRON_RUN_AS_NODE: '1', DSH_DESKTOP_NODE_EXECUTABLE: executable,
    PATH: `${runtimeBin}:${env.PATH ?? ''}`, DSH_HOME: home,
    XDG_CONFIG_HOME: join(home, 'config'), XDG_CACHE_HOME: join(home, 'cache'), XDG_STATE_HOME: join(home, 'state'),
    DSH_TELEMETRY_DISABLED: '1', OTEL_SDK_DISABLED: 'true', DSH_PERMISSION_MODE: 'danger-full-access',
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: '1', FUNNYWORK_PACKAGED_SMOKE_API_KEY: 'local-fixture-only',
    pnpm_config_verify_deps_before_run: 'false', pnpm_config_store_dir: join(home, 'pnpm-store'),
    pnpm_config_userconfig: join(home, 'npmrc'), pnpm_config_globalconfig: join(home, 'npmrc'),
    npm_config_userconfig: join(home, 'npmrc'), npm_config_globalconfig: join(home, 'npmrc'),
  }
}

/** Resolve from the archive using Electron's ASAR-aware resolver, and execute its installed MCP CLI. */
async function inspectRuntime(root) {
  const require = createRequire(join(root, 'package.json'))
  const bundle = require.resolve(`${capabilityBundle}/package.json`)
  const bundleManifest = JSON.parse(readFileSync(bundle, 'utf8'))
  const bundlePatch = join(dirname(bundle), bundleManifest.dsh.bundle.patch)
  assert(readFileSync(bundlePatch, 'utf8').includes('@deepseek-ai/dsh-funnywork-desktop-controls'), 'Packaged Desktop bundle patch did not select its runtime plugin')
  const controls = require.resolve('@deepseek-ai/dsh-funnywork-desktop-controls/package.json')
  const browser = require.resolve('@deepseek-ai/dsh-experimental-browser-use-playwright-mcp/package.json')
  const mcp = createRequire(browser).resolve('@playwright/mcp/package.json')
  const cli = join(dirname(mcp), 'cli.js')
  const cliVersion = execFileSync(process.execPath, [cli, '--version'], {
    env: process.env, encoding: 'utf8', timeout: 30000, maxBuffer: 65536,
  }).trim()
  const native = require.resolve('@deepseek-ai/dsh-experimental-cua-macos/package.json')
  const helper = join(dirname(native).replace('.asar/', '.asar.unpacked/'), 'native', 'bin', `darwin-${process.arch}`, 'dsh-cua-native')
  await access(helper, constants.X_OK)
  let profileManager
  try { profileManager = require.resolve('@deepseek-ai/dsh-profile-manager') } catch { /* Not a package in every DSH release. */ }
  return {
    runtimeVersion: JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version,
    bundle, bundlePatch, controls, playwrightProvider: browser, playwrightCli: cli, playwrightVersion: cliVersion,
    nativeProvider: native, nativeHelper: helper, nativeExecutable: true, profileManager: profileManager ?? null,
  }
}

/** Use the packaged default initializer when exported; otherwise use the shell's compiled normal initializer. */
async function initializeProfile(root, profile, fallback) {
  const require = createRequire(join(root, 'package.json'))
  let packaged
  try { packaged = require.resolve('@deepseek-ai/dsh-profile-manager') } catch { /* See explicit fallback report. */ }
  if (packaged !== undefined) {
    const module = await import(pathToFileURL(packaged).href)
    if (typeof module.createPluginProfile === 'function') {
      module.createPluginProfile(profile)
      return { source: 'packaged-profile-manager', path: packaged }
    }
  }
  const module = await import(pathToFileURL(fallback).href)
  assert(typeof module.createPluginProfile === 'function', 'Compiled Desktop profile initializer is unavailable')
  module.createPluginProfile(profile)
  return { source: 'checkout-compiled-desktop-initializer', path: fallback,
    limitation: 'Default selection comes from the compiled shell source; all loaded runtime, model, browser and native code comes from the supplied application.' }
}

function pluginSource(workspace, fixtureExecutable) {
  return `
import { createUserMessage, ToolCallId } from '@deepseek-ai/dsh-llm'
import { SessionId } from '@deepseek-ai/dsh-session'
import { inspect } from 'node:util'
export function apply(ctx) {
  let running = false
  let activeAgent
  let applicationApprovals = 0
  const agentErrors = []
  ctx.on('agent/error', ({ agent, error }) => { if (agent === activeAgent) agentErrors.push(inspect(error, { depth: 4 })) })
  ctx.on('user-questions/request', async (request, next) => {
    if (request.agent !== activeAgent) return next()
    const question = request.questions[0]
    if (${JSON.stringify(fixtureExecutable ?? null)} === null || request.questions.length !== 1 || question?.id !== 'cua-app-access'
      || question.detail?.split('\\n')[0] !== 'Application: ' + ${JSON.stringify(fixtureExecutable ?? null)}
      || !question.options?.some(option => option.label === 'Allow for this task')) {
      throw new Error('Smoke refused an application request outside the exact task-owned fixture')
    }
    applicationApprovals++
    console.info('Packaged smoke: granted task-only access to the exact fixture application')
    return { answers: [{ id: 'cua-app-access', selected: ['Allow for this task'] }] }
  }, { prepend: true })
  ctx.effect(() => ctx.webServer.register({ kind: 'exact', path: '/funnywork-capability-smoke',
    async handler(_request, response) {
      if (running) { response.statusCode = 409; response.end('Smoke already started'); return }
      running = true
      let handle
      try {
        handle = await ctx.agents.create({ sessionId: SessionId('packaged-capability-fixture'),
          agentOptions: { provider: 'smoke-loopback', model: 'smoke-text' }, meta: { cwd: ${JSON.stringify(workspace)} } })
        const agent = handle.agent
        activeAgent = agent
        const names = ctx.tools.schemas(agent).map(tool => tool.name)
        for (const name of ${JSON.stringify([...browserTools, 'browser_use_status', 'cua_status', 'cua'])}) {
          if (!names.includes(name)) throw new Error('Packaged default tool missing: ' + name)
        }
        agent.followup(createUserMessage({ content: [{ type: 'text', text: 'Complete the local synthetic Employee name form, read it back, inspect native availability, and when provided enter Chinese text into only the task-owned AppKit fixture and submit it.' }], source: { kind: 'user' } }))
        await agent.whenIdle()
        const messages = agent.session.deriveMessages()
        if (!messages.some(message => message.role === 'assistant' && message.content.some(block => block.type === 'text' && block.text.includes(${JSON.stringify(sentinel)})))) {
          throw new Error('Normal AgentLoop did not complete the fixture: ' + JSON.stringify({ agentErrors, messages: messages.slice(-2),
            events: agent.session.snapshotEvents().map(event => ({ type: event.type, ...(event.type === 'turn/end' ? { data: event.data } : {}) })) }))
        }
        if (messages.some(message => message.content.some(block => block.type === 'image'))) throw new Error('Text-only fixture admitted an image')
        const status = await ctx.tools.execute({ agent, name: 'cua_status', arguments: {},
          callId: ToolCallId('packaged-native-diagnostic'), signal: AbortSignal.timeout(30000) })
        if (status.isError || status.value?.available !== true || status.value?.native?.kind !== 'ready') {
          throw new Error('Packaged native helper was unavailable: ' + JSON.stringify(status))
        }
        if (!status.value.native.helperPath.includes('.asar.unpacked/')) throw new Error('Native helper did not use physical ASAR-unpacked path')
        const browserStatus = await ctx.tools.execute({ agent, name: 'browser_use_status', arguments: {},
          callId: ToolCallId('packaged-browser-diagnostic'), signal: AbortSignal.timeout(10000) })
        if (browserStatus.isError) throw new Error('Packaged browser status failed')
        const browser = JSON.parse(browserStatus.value)
        if (browser.status !== 'configured' || browser.imageResponses !== 'omit' || browser.runtimeDownloads !== false) throw new Error('Packaged browser did not retain text-only installed-runtime configuration')
        const result = { normalAgentLoop: true, textOnly: true, tools: names.filter(name => name === 'cua' || name === 'cua_status' || name === 'browser_use_status' || name.startsWith('mcp__playwright-mcp__')),
          native: status.value, browser, messageCount: messages.length, applicationApprovals, screenshotCount: 0 }
        if (${JSON.stringify(fixtureExecutable !== undefined)} && applicationApprovals !== 1) throw new Error('Expected exactly one task-scoped fixture application approval')
        await handle.dispose()
        handle = undefined
        result.agentDisposed = ctx.agents.get(agent.id) === undefined
        result.sessionBrowserToolsRemoved = !ctx.tools.schemas(agent).some(tool => tool.name.startsWith('mcp__playwright-mcp__'))
        if (!result.agentDisposed || !result.sessionBrowserToolsRemoved) throw new Error('Session-scoped controls survived disposal')
        response.setHeader('content-type', 'application/json')
        response.end(JSON.stringify(result))
      } catch (error) {
        response.statusCode = 500
        response.end(error instanceof Error ? error.stack : String(error))
      } finally { activeAgent = undefined; await handle?.dispose() }
    } }))
}
`
}

function textContent(content) {
  return typeof content === 'string' ? content : (content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
}

function nativeResult(text, label) {
  let result
  try { result = JSON.parse(text) } catch { throw new Error(`${label} was not a canonical native JSON result: ${text}`) }
  assert(result && typeof result === 'object', `${label} did not return an object`)
  return result
}

function axElement(tree, label) {
  assert(typeof tree === 'string', 'Native AX tree was absent')
  const matches = tree.split('\n').filter(line => line.includes(label)).map(line => /\[(\d+)\]/u.exec(line)?.[1]).filter(Boolean)
  assert(matches.length === 1, `Expected one indexed native fixture element: ${label}`)
  return Number(matches[0])
}

/** Deterministic OpenAI-compatible SSE response; the actual pi-ai adapter and AgentLoop consume it. */
function mockStep(body, state, origin) {
  assert(body.model === 'smoke-text', 'Unexpected mock model')
  assert(body.stream === true, 'Fixture requires the normal streaming model path')
  assert(!JSON.stringify(body.messages).includes('"type":"image_url"'), 'Mock text model received an image')
  const names = (body.tools ?? []).map(tool => tool.function?.name)
  for (const name of [...browserTools, 'cua_status', 'cua']) assert(names.includes(name), `Model request omitted tool ${name}`)
  const latest = [...body.messages].reverse().find(message => message.role === 'tool')
  const text = textContent(latest?.content)
  let call
  switch (state.requests++) {
    case 0: call = [browserTools[0], { url: `${origin}/fixture` }]; break
    case 1: call = [browserTools[1], {}]; break
    case 2: {
      const textbox = /textbox "Employee name" \[ref=([^\]]+)\]/u.exec(text)?.[1]
      state.button = /button "Save" \[ref=([^\]]+)\]/u.exec(text)?.[1]
      assert(textbox && state.button, 'Real AX snapshot did not expose fixture form targets')
      call = [browserTools[2], { fields: [{ name: 'Employee name', type: 'textbox', target: textbox, value: 'Ada' }] }]
      state.axSnapshotRead = true
      break
    }
    case 3: call = [browserTools[3], { target: state.button, element: 'Save' }]; break
    case 4: call = [browserTools[1], {}]; break
    case 5:
      assert(text.includes('Saved Ada'), 'Model did not read the submitted real DOM value')
      state.formReadBack = true
      call = ['cua_status', {}]
      break
    case 6: {
      assert(text.includes('"available":true') && text.includes('.asar.unpacked/'), 'Native status did not reach the model from the packaged helper')
      state.nativeStatusRead = true
      if (state.fixtureApp) {
        const status = nativeResult(text, 'cua_status')
        state.nativePermissions = status.capabilities?.permissions
        assert(state.nativePermissions?.accessibility === true, 'Packaged helper lacks Accessibility permission for real fixture actions; this test never grants OS permissions')
        call = ['cua', { method: 'getApp', params: { selector: state.fixtureApp } }]
      }
      break
    }
    case 7: {
      const selected = nativeResult(text, 'getApp')
      assert(selected.pid === state.fixturePid && typeof selected.appHandle === 'string', 'Native getApp selected an application other than the launched task-owned fixture')
      state.appHandle = selected.appHandle
      call = ['cua', { method: 'getState', params: { appHandle: state.appHandle, details: true } }]
      break
    }
    case 8: {
      const observed = nativeResult(text, 'initial getState')
      assert(observed.appHandle === state.appHandle && typeof observed.snapshotId === 'string', 'Native observation identity did not match the fixture')
      call = ['cua', { method: 'setValue', params: { appHandle: state.appHandle, snapshotId: observed.snapshotId,
        elementIndex: axElement(observed.tree, 'Fixture editable input'), value: chinese } }]
      state.nativeAxSnapshotRead = true
      break
    }
    case 9:
      nativeResult(text, 'setValue')
      call = ['cua', { method: 'getState', params: { appHandle: state.appHandle } }]
      break
    case 10: {
      const observed = nativeResult(text, 'Chinese read-back getState')
      assert(observed.appHandle === state.appHandle && observed.tree.includes(chinese), 'Chinese input was absent from the fresh native AX observation')
      state.nativeChineseReadBack = true
      call = ['cua', { method: 'click', params: { appHandle: state.appHandle, snapshotId: observed.snapshotId,
        elementIndex: axElement(observed.tree, 'Submit fixture value') } }]
      break
    }
    case 11:
      nativeResult(text, 'click')
      call = ['cua', { method: 'getState', params: { appHandle: state.appHandle } }]
      break
    case 12: {
      const observed = nativeResult(text, 'submitted getState')
      assert(observed.appHandle === state.appHandle && observed.tree.includes(`Result: ${chinese}`), 'Submitted Chinese value was absent from the real AX tree')
      state.nativeSubmissionReadBack = true
      break
    }
    default: throw new Error('Agent exceeded the expected fixture steps')
  }
  if (call?.[0] === 'cua') (state.nativeModelMethods ??= []).push(call[1].method)
  state.lastModelResponse = call === undefined ? 'final text' : call[0] === 'cua' ? `cua.${call[1].method}` : call[0]
  const delta = call === undefined ? { role: 'assistant', content: sentinel } : {
    role: 'assistant', tool_calls: [{ index: 0, id: `fixture_${state.requests}`, type: 'function',
      function: { name: call[0], arguments: JSON.stringify(call[1]) } }],
  }
  return [
    { id: `fixture_${state.requests}`, object: 'chat.completion.chunk', created: 1, model: 'smoke-text', choices: [{ index: 0, delta, finish_reason: null }] },
    { id: `fixture_${state.requests}`, object: 'chat.completion.chunk', created: 1, model: 'smoke-text', choices: [{ index: 0, delta: {}, finish_reason: call ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } },
  ].map(value => `data: ${JSON.stringify(value)}\n\n`).join('') + 'data: [DONE]\n\n'
}

async function fixtureServer(nativeFixture) {
  const state = { requests: 0, axSnapshotRead: false, formReadBack: false, nativeStatusRead: false, errors: [],
    ...(nativeFixture ? { fixtureApp: nativeFixture.app, fixturePid: nativeFixture.pid } : {}) }
  let origin
  const server = createServer(async (request, response) => {
    try {
      if (request.url === '/fixture' && request.method === 'GET') {
        response.setHeader('content-type', 'text/html; charset=utf-8')
        response.end('<!doctype html><title>Packaged capability fixture</title><form><label>Employee name <input name="employee"></label><button>Save</button></form><output></output><script>document.querySelector("form").onsubmit=e=>{e.preventDefault();document.querySelector("output").textContent="Saved "+document.querySelector("input").value}</script>')
        return
      }
      if (request.url === '/favicon.ico') { response.statusCode = 204; response.end(); return }
      assert(request.url === '/v1/chat/completions' && request.method === 'POST', 'Unexpected loopback request')
      let data = ''
      for await (const chunk of request) {
        data += chunk
        assert(data.length < 4_000_000, 'Fixture request exceeded bound')
      }
      const frames = mockStep(JSON.parse(data), state, origin)
      console.log(`Local fixture model request ${state.requests}: ${state.lastModelResponse}`)
      response.setHeader('content-type', 'text/event-stream')
      response.end(frames)
    } catch (error) {
      state.errors.push(error instanceof Error ? error.message : String(error))
      response.statusCode = 400
      response.end(JSON.stringify({ error: { message: state.errors.at(-1), type: 'fixture_error' } }))
    }
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  origin = `http://127.0.0.1:${server.address().port}`
  return { state, origin, close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections() }) }
}

async function selfCheck() {
  for (const useNative of [false, true]) {
    const state = { requests: 0, ...(useNative ? { fixtureApp: '/synthetic/FunnyWorkAXFixture.app', fixturePid: 42 } : {}) }
    const body = { model: 'smoke-text', stream: true, tools: [...browserTools, 'cua_status', 'cua'].map(name => ({ function: { name } })), messages: [] }
    const expected = useNative ? 13 : 7
    for (let i = 0; i < expected; i++) {
      let content = 'fixture'
      if (i === 2) content = 'textbox "Employee name" [ref=e1]\nbutton "Save" [ref=e2]'
      if (i === 5) content = 'Saved Ada'
      if (i === 6) content = '{"available":true,"native":{"helperPath":"/synthetic/app.asar.unpacked/helper"},"capabilities":{"permissions":{"accessibility":true,"screenRecording":false}}}'
      if (i === 7) content = '{"appHandle":"fixture-42","pid":42}'
      if (i >= 8) content = JSON.stringify({ appHandle: 'fixture-42', snapshotId: `snapshot-${i}`,
        tree: `[1] Fixture editable input value="${i >= 10 ? chinese : ''}"\n[2] Submit fixture value\n${i === 12 ? `Result: ${chinese}` : ''}` })
      body.messages.push({ role: 'tool', content })
      assert(mockStep(body, state, 'http://127.0.0.1:1').endsWith('data: [DONE]\n\n'), 'Invalid SSE terminator')
    }
    assert(state.requests === expected && state.formReadBack && state.nativeStatusRead, 'Mock protocol self-check failed')
    if (useNative) assert(state.nativeChineseReadBack && state.nativeSubmissionReadBack && state.nativeModelMethods.length === 6, 'Native model-loop protocol self-check failed')
  }
  execFileSync(process.execPath, ['--check', '--input-type=module'], { input: pluginSource('/synthetic/fixture', '/synthetic/FunnyWorkAXFixture.app/Contents/MacOS/FunnyWorkAXFixture'), encoding: 'utf8' })
  console.log('Packaged capability smoke: script, generated plugin and deterministic mock protocol passed; no application/model launched.')
}

function internalRun(executable, args, env, cwd) {
  const output = execFileSync(executable, ['--expose-internals', fileURLToPath(import.meta.url), ...args], {
    env, cwd, encoding: 'utf8', timeout: 60000, maxBuffer: 1024 * 1024,
  })
  const line = output.split('\n').find(line => line.startsWith(marker))
  assert(line, 'Packaged runtime inspection did not return its result')
  return JSON.parse(line.slice(marker.length))
}

function startHost(executable, root, profile, runtime, env, home) {
  const entry = join(root, 'node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js')
  const primary = join(runtime, 'primary-runtime')
  const child = spawn(executable, ['--expose-internals', entry, root, profile, primary,
    join(primary, 'dependencies/pnpm/bin/pnpm.mjs'), join(runtime, 'bin')], {
    cwd: home, env, stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  })
  let tail = ''
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { tail = (tail + chunk).slice(-24000) })
  const exited = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })))
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Packaged Host readiness exceeded 120 seconds')), 120000)
    child.once('error', error => { clearTimeout(timer); reject(error) })
    child.once('exit', () => { clearTimeout(timer); reject(new Error(`Packaged Host exited before readiness: ${tail}`)) })
    child.on('message', message => {
      if (message.type === 'ready') { clearTimeout(timer); resolve(message) }
      if (message.type === 'fatal') { clearTimeout(timer); reject(new Error(`Packaged Host fatal: ${message.diagnostic ?? message.message}`)) }
    })
  })
  return { pid: child.pid, ready, tail: () => tail.replace(/([?&]token=)[^\s&]+/gu, '$1<redacted>'), async stop() {
    if (child.exitCode !== null || child.signalCode !== null) return await exited
    if (child.connected) child.send({ type: 'shutdown' })
    let timer
    const result = await Promise.race([exited, new Promise(resolve => { timer = setTimeout(() => resolve(undefined), 30000) })])
    clearTimeout(timer)
    if (result) return result
    child.kill('SIGTERM')
    let killTimer
    const terminated = await Promise.race([exited, new Promise(resolve => { killTimer = setTimeout(() => resolve(undefined), 10000) })])
    clearTimeout(killTimer)
    if (!terminated) { child.kill('SIGKILL'); await exited }
    throw new Error('Packaged Host required forced shutdown')
  } }
}

function processes() {
  return new Map(execFileSync('/bin/ps', ['-eo', 'pid=,ppid=,stat=,lstart='], { encoding: 'utf8' }).trim().split('\n').map(line => {
    const [pid, parent, state, ...started] = line.trim().split(/\s+/u)
    return [Number(pid), { parent: Number(parent), state, started: started.join(' ') }]
  }))
}

function trackChildren(known) {
  const current = processes()
  const visited = new Set()
  let frontier = [process.pid]
  while (frontier.length) {
    const parents = new Set(frontier)
    frontier = [...current].filter(([pid, item]) => parents.has(item.parent) && !visited.has(pid)).map(([pid]) => pid)
    for (const pid of frontier) { visited.add(pid); if (!known.has(pid)) known.set(pid, current.get(pid).started) }
  }
}

async function waitForChildren(known) {
  let active = []
  for (let i = 0; i < 40; i++) {
    const current = processes()
    active = [...known].filter(([pid, started]) => current.get(pid)?.started === started && !current.get(pid).state.startsWith('Z')).map(([pid]) => pid)
    if (active.length === 0) return []
    await new Promise(resolve => setTimeout(resolve, 125))
  }
  return active
}

/** Failure cleanup is limited to captured identities; a cross-Host shared native helper is never killed. */
async function cleanupChildren(known) {
  const active = await waitForChildren(known)
  const sharedNativeHelpers = []
  const owned = new Map()
  for (const pid of active) {
    let command
    try { command = execFileSync('/bin/ps', ['-p', String(pid), '-o', 'comm='], { encoding: 'utf8' }).trim() } catch { continue }
    if (basename(command) === 'dsh-cua-native') sharedNativeHelpers.push(pid)
    else owned.set(pid, known.get(pid))
  }
  const signalOwned = signal => {
    const current = processes()
    for (const [pid, started] of owned) {
      if (current.get(pid)?.started !== started) continue
      try { process.kill(pid, signal) } catch (error) { if (error.code !== 'ESRCH') throw error }
    }
  }
  if (owned.size) {
    signalOwned('SIGTERM')
    if ((await waitForChildren(owned)).length) signalOwned('SIGKILL')
  }
  return { tracked: known.size, forcedCleanup: [...owned.keys()], sharedNativeHelpers,
    stillActive: await waitForChildren(owned),
    nativeIpcException: '/tmp/dsh-cua-<uid> is the upstream owner-only shared socket/lease directory; it contains only small IPC and lease files, never build or fixture data.' }
}

/** Resolve an existing ancestor so a lexical /Volumes/SSD path cannot escape through a symlink. */
async function destination(path) {
  let parent = resolve(path)
  const suffix = []
  while (!existsSync(parent)) {
    suffix.unshift(basename(parent))
    const next = dirname(parent)
    assert(next !== parent, `No existing parent for ${path}`)
    parent = next
  }
  return join(await realpath(parent), ...suffix)
}

async function waitUntil(check, label, timeout = 30000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const result = await check()
    if (result) return result
    await new Promise(resolve => setTimeout(resolve, 125))
  }
  throw new Error(`${label} exceeded ${timeout / 1000} seconds`)
}

function launchNativeFixture(app, statePath, env) {
  const executable = join(app, 'Contents/MacOS/FunnyWorkAXFixture')
  const fixtureEnv = { ...env }
  delete fixtureEnv.ELECTRON_RUN_AS_NODE
  const child = spawn(executable, [statePath], { cwd: dirname(statePath), env: fixtureEnv, stdio: ['ignore', 'pipe', 'pipe'] })
  let tail = ''
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { tail = (tail + chunk).slice(-4000) })
  const exited = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })))
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Task AppKit fixture did not become ready: ${tail}`)), 15000)
    child.once('error', error => { clearTimeout(timer); reject(error) })
    child.once('exit', () => { clearTimeout(timer); reject(new Error(`Task AppKit fixture exited early: ${tail}`)) })
    child.stdout.on('data', chunk => { if (String(chunk).includes('READY')) { clearTimeout(timer); resolve() } })
  })
  return { app, executable, pid: child.pid, statePath, ready, async stop() {
    if (child.exitCode !== null || child.signalCode !== null) return await exited
    child.kill('SIGTERM')
    let timer
    const result = await Promise.race([exited, new Promise(resolve => { timer = setTimeout(() => resolve(undefined), 10000) })])
    clearTimeout(timer)
    if (result) return result
    child.kill('SIGKILL')
    await exited
    throw new Error('Task-owned AppKit fixture required forced termination')
  } }
}

async function cdp(url) {
  const socket = new WebSocket(url)
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.close(); reject(new Error('CDP connection exceeded ten seconds')) }, 10000)
    socket.addEventListener('open', () => { clearTimeout(timer); resolve() }, { once: true })
    socket.addEventListener('error', error => { clearTimeout(timer); reject(error) }, { once: true })
  })
  let counter = 0
  const pending = new Map()
  socket.addEventListener('message', event => {
    const message = JSON.parse(String(event.data))
    const call = pending.get(message.id)
    if (call === undefined) return
    pending.delete(message.id)
    clearTimeout(call.timer)
    if (message.error) call.reject(new Error(JSON.stringify(message.error)))
    else call.resolve(message.result)
  })
  return {
    call(method, params = {}) {
      return new Promise((resolve, reject) => {
        const id = ++counter
        const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP ${method} timed out`)) }, 10000)
        pending.set(id, { resolve, reject, timer })
        socket.send(JSON.stringify({ id, method, params }))
      })
    },
    close() { socket.close() },
  }
}

/** Launch the actual application shell up to its unmodified account gate; never submit credentials. */
async function smokeGui(executable, env, home, origin) {
  const userData = join(home, 'electron-user-data')
  const logs = join(home, 'electron-logs')
  await mkdir(userData, { recursive: true, mode: 0o700 })
  await writeFile(join(home, 'funnyworker-backend.json'), JSON.stringify({ url: origin }), { mode: 0o600, flag: 'wx' })
  const guiEnv = { ...env, DSH_DESKTOP_LOG_DIR: logs, DSH_DESKTOP_DIAGNOSTIC_FILE: join(home, 'gui-main-error.txt') }
  delete guiEnv.ELECTRON_RUN_AS_NODE
  const child = spawn(executable, [`--user-data-dir=${userData}`, '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0'], {
    cwd: home, env: guiEnv, stdio: ['ignore', 'pipe', 'pipe'],
  })
  let tail = ''
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { tail = (tail + chunk).slice(-8000) })
  const exited = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })))
  let connection
  let result
  try {
    child.once('error', error => { tail += error.message })
    const active = join(userData, 'DevToolsActivePort')
    const port = await waitUntil(() => {
      if (child.exitCode !== null || child.signalCode !== null) throw new Error(`Packaged GUI exited: ${tail}`)
      if (!existsSync(active)) return Number(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//u.exec(tail)?.[1]) || undefined
      return Number(readFileSync(active, 'utf8').split('\n')[0]) || undefined
    }, 'Packaged GUI debugger readiness')
    console.log(`Packaged GUI debugger ready on loopback port ${port}`)
    let debuggerTargets = []
    let debuggerError
    let sampled = false
    const page = await waitUntil(async () => {
      if (child.exitCode !== null || child.signalCode !== null) throw new Error(`Packaged GUI exited: ${tail}`)
      try {
        const response = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(2000) })
        if (!response.ok) { debuggerError = `HTTP ${response.status}`; return undefined }
        const targets = await response.json()
        debuggerTargets = targets.map(target => ({ type: target.type, url: target.url }))
        return targets.find(target => target.type === 'page' && /welcome\.html/u.test(target.url))
      } catch (error) {
        if (debuggerError === undefined) console.log(`Packaged GUI debugger is still starting: ${String(error)}`)
        if (!sampled && child.exitCode === null && child.signalCode === null) {
          sampled = true
          try {
            execFileSync('/usr/bin/sample', [String(child.pid), '1', '10', '-file', join(home, 'gui-startup.sample.txt')], {
              encoding: 'utf8', timeout: 8000, maxBuffer: 65536,
            })
          } catch { /* Sampling is diagnostic; it never changes startup or the acceptance result. */ }
        }
        debuggerError = String(error)
        return undefined
      } // The HTTP endpoint can lag Chromium's listening notification during startup.
    }, 'Packaged login window', 45000).catch(error => { throw new Error(`${String(error)}; targets=${JSON.stringify(debuggerTargets)}; last transport error=${debuggerError ?? 'none'}`) })
    connection = await cdp(page.webSocketDebuggerUrl)
    const document = await waitUntil(async () => {
      const value = await connection.call('Runtime.evaluate', { returnByValue: true, expression: `JSON.stringify({
        ready: document.readyState, visible: document.visibilityState === 'visible', title: document.title, text: document.body?.innerText ?? '',
        passwordInputs: [...document.querySelectorAll('input[type="password"]')].filter(input => input.getClientRects().length > 0).length,
        empty: [...document.querySelectorAll('input[type="text"], input[type="email"], input[type="password"]')].every(input => input.value === '')
      })` })
      const state = JSON.parse(value.result.value)
      return state.ready === 'complete' && state.visible && state.passwordInputs > 0 && /登录|Sign in|Log in/iu.test(state.text) ? state : undefined
    }, 'Packaged visible login form')
    assert(document.empty, 'Fresh packaged login form unexpectedly had saved input')
    result = { started: true, accountGatePreserved: true, page: 'renderer/welcome.html', visiblePasswordInputs: document.passwordInputs,
      emptyInputs: true, credentialsSubmitted: false, modelRequests: 0, userData, logs, homeEnvironmentPreserved: guiEnv.HOME === process.env.HOME,
      limitation: 'Login-window smoke only. Native permission diagnostics below belong to the packaged Host launched in Node mode; they do not establish permission grants for a future authenticated GUI session.' }
    // Closing the empty login window takes the application's normal cancel/quit path.
    await connection.call('Runtime.evaluate', { expression: 'window.close()' }).catch(() => {})
    const exit = await waitUntil(() => child.exitCode === null && child.signalCode === null ? undefined : { code: child.exitCode, signal: child.signalCode }, 'Packaged GUI normal quit')
    result.exit = exit
    assert(exit.code === 0, 'Packaged GUI did not quit successfully')
    return result
  } catch (error) {
    throw new Error(`Packaged login-window smoke failed: ${String(error)}; guiPid=${child.pid}; exitCode=${child.exitCode}; signal=${child.signalCode}\n${tail}`)
  } finally {
    connection?.close()
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM')
      let timer
      const stopped = await Promise.race([exited, new Promise(resolve => { timer = setTimeout(() => resolve(undefined), 10000) })])
      clearTimeout(timer)
      if (!stopped) { child.kill('SIGKILL'); await exited }
    }
  }
}

async function main(input) {
  if (input['self-check']) { await selfCheck(); return }
  assert(process.platform === 'darwin', 'This packaged native smoke currently targets macOS')
  for (const key of ['app', 'home', 'report']) assert(typeof input[key] === 'string' && isAbsolute(input[key]), `--${key} requires an absolute path`)
  const external = await realpath('/Volumes/SSD')
  const projectLocal = await realpath(join(project, '.local'))
  const home = resolve(input.home)
  const canonicalHome = await destination(home)
  assert(canonicalHome.startsWith(`${projectLocal}/`), '--home must be a new directory under this checkout .local')
  assert(!existsSync(home), '--home must not exist; the script never reuses or deletes a user profile')
  for (const path of [home, input.report, process.env.TMPDIR, process.env.TMP, process.env.TEMP]) {
    assert(typeof path === 'string' && isAbsolute(path), 'Home, report and all temporary paths require absolute paths')
    const canonical = await destination(path)
    assert(canonical === external || canonical.startsWith(`${external}/`), 'Home, report and all temporary paths must resolve to /Volumes/SSD')
  }
  assert(!(await destination(input.report)).startsWith(`${canonicalHome}/`), '--report must be outside the disposable --home')
  assert(!existsSync(input.report), '--report must be a new file')
  const app = await realpath(input.app)
  let fixtureApp
  if (input['fixture-app']) {
    assert(isAbsolute(input['fixture-app']), '--fixture-app requires an absolute task-owned application path')
    fixtureApp = await realpath(input['fixture-app'])
    const taskApps = await realpath(join(projectLocal, 'agent/apps'))
    assert(fixtureApp.startsWith(`${taskApps}/`) && basename(fixtureApp) === 'FunnyWorkAXFixture.app', 'Native fixture must be the task-owned FunnyWorkAXFixture.app under this checkout .local/agent/apps')
    const identifier = execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleIdentifier', join(fixtureApp, 'Contents/Info.plist')], { encoding: 'utf8' }).trim()
    assert(identifier === 'com.funnywork.tests.ax-fixture', 'Unexpected native fixture application identifier')
    await access(join(fixtureApp, 'Contents/MacOS/FunnyWorkAXFixture'), constants.X_OK)
  }
  const info = execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleExecutable', join(app, 'Contents/Info.plist')], { encoding: 'utf8' }).trim()
  const executable = join(app, 'Contents/MacOS', info)
  const resources = join(app, 'Contents/Resources')
  const root = join(resources, 'app.asar/dsh')
  const runtime = join(resources, 'runtime')
  await mkdir(home, { recursive: true, mode: 0o700 })
  await writeFile(join(home, 'npmrc'), '', { mode: 0o600, flag: 'wx' })
  const workspace = join(home, 'workspace')
  await mkdir(workspace, { mode: 0o700 })
  const env = isolatedEnvironment(home, executable, join(runtime, 'bin'))
  const report = { status: 'failed', timestamp: new Date().toISOString(), app, home, workspace,
    scope: input['gui-only'] ? 'gui-only; no model/AgentLoop actions in this run' : 'packaged AgentLoop and optional login-window smoke',
    model: input['gui-only'] ? 'not invoked' : 'loopback synthetic OpenAI-compatible text model',
    externalModelRequests: 0, homeEnvironmentPreserved: env.HOME === process.env.HOME }
  let host
  let fixture
  let nativeFixture
  const children = new Map()
  let processTracker
  let passed = false
  try {
    report.packaged = internalRun(executable, ['--inspect-runtime', root], env, home)
    assert(report.packaged.bundle.includes('.asar/'), 'Capability bundle did not resolve inside the application archive')
    if (input['gui-only']) {
      assert(!fixtureApp, '--gui-only does not run the AppKit/model fixture')
      fixture = await fixtureServer()
      report.fixture = fixture.state
      trackChildren(children)
      processTracker = setInterval(() => trackChildren(children), 150)
      report.gui = await smokeGui(executable, env, home, fixture.origin)
      report.scope = 'gui-only; no model/AgentLoop actions in this run'
    } else {
    const profile = join(home, 'profiles/desktop')
    const initializer = join(project, 'apps/desktop/apps/desktop/lib/types/project-manager.js')
    report.defaultProfileInitialization = internalRun(executable, ['--initialize-profile', root, profile, initializer], env, home)
    const manifestPath = join(profile, 'package.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    report.defaultBundles = [...manifest.dsh.profile.bundles]
    assert(report.defaultBundles.includes(capabilityBundle), 'Normal Desktop initializer omitted the capability bundle')
    trackChildren(children)
    processTracker = setInterval(() => trackChildren(children), 150)
    if (fixtureApp) {
      nativeFixture = launchNativeFixture(fixtureApp, join(home, 'appkit-fixture-state.json'), env)
      await nativeFixture.ready
      const initial = JSON.parse(readFileSync(nativeFixture.statePath, 'utf8'))
      assert(initial.pid === nativeFixture.pid && initial.submissions === 0 && initial.currentValue === '' && initial.submittedValue === '', 'AppKit fixture did not start with fresh empty state')
      report.appkitFixture = { app: fixtureApp, pid: nativeFixture.pid, statePath: nativeFixture.statePath, initial }
    }
    fixture = await fixtureServer(nativeFixture)
    report.fixture = fixture.state
    const plugin = join(profile, 'node_modules', pluginName)
    await mkdir(plugin, { recursive: true, mode: 0o700 })
    await writeFile(join(plugin, 'package.json'), JSON.stringify({ name: pluginName, version: '1.0.0', type: 'module', exports: './index.js', dsh: { bundle: { patch: './bundle.yml' } } }))
    await writeFile(join(plugin, 'index.js'), pluginSource(workspace, nativeFixture?.executable))
    await writeFile(join(plugin, 'bundle.yml'), JSON.stringify([{ insert: [{ id: pluginName, name: pluginName, inject: ['webServer', 'agents', 'tools', 'userQuestions'] }] }]))
    manifest.dependencies[pluginName] = `file:${plugin}`
    manifest.dsh.profile.bundles.push(pluginName)
    await writeFile(manifestPath, JSON.stringify(manifest, null, 2), { mode: 0o600 })
    const patch = [
      { id: 'webserver', config: { host: '127.0.0.1', port: 0 } },
      { id: 'agent-default-model', config: { provider: 'smoke-loopback', model: 'smoke-text' } },
      { id: 'llm-pi-ai', config: { enterprise: false, providers: { 'smoke-loopback': { api: 'openai-completions',
        baseURL: `${fixture.origin}/v1`, apiKeyEnv: 'FUNNYWORK_PACKAGED_SMOKE_API_KEY',
        models: [{ id: 'smoke-text', input: ['text'], contextWindow: 262144, maxTokens: 2048 }] } } } },
      ...['llm-deepseek', 'llm-deepseek-account', 'session-title-llm', 'session-log-deepseek',
        'session-telemetry-otel', 'desktop-product-telemetry', 'product-analytics'].map(id => ({ id, disabled: true })),
    ]
    if (input.headless || input.browser) patch.push({ id: 'funnywork-desktop-capabilities', config: {
      browser: { enabled: true, headless: input.headless === true, ...(input.browser ? { executablePath: input.browser } : {}) }, computer: { enabled: true },
    } })
    await writeFile(join(profile, 'cordis.patch.yml'), JSON.stringify(patch), { mode: 0o600 })
    host = startHost(executable, root, profile, runtime, env, workspace)
    const ready = await host.ready
    const login = await fetch(ready.url, { redirect: 'manual', signal: AbortSignal.timeout(10000) })
    const cookie = login.headers.getSetCookie().map(value => value.split(';')[0]).join('; ')
    const response = await fetch(new URL('/funnywork-capability-smoke', ready.url), { headers: { cookie }, signal: AbortSignal.timeout(180000) })
    const body = await response.text()
    assert(response.ok, `Packaged AgentLoop fixture failed: ${body}`)
    report.loop = JSON.parse(body)
    report.nativePermissions = { values: report.loop.native.capabilities?.permissions ?? null,
      scope: 'Packaged native helper reached by the packaged desktop-host under Electron Node mode',
      accessibilityGranted: report.loop.native.capabilities?.permissions?.accessibility === true,
      limitation: 'Helper execution and AX permission are separate. A missing grant requires the user to allow the packaged application in System Settings; this smoke never requests a grant.' }
    report.fixture = fixture.state
    const expectedRequests = nativeFixture ? 13 : 7
    assert(fixture.state.requests === expectedRequests && fixture.state.errors.length === 0 && fixture.state.formReadBack && fixture.state.nativeStatusRead, 'The local model did not finish its real tool loop')
    if (nativeFixture) {
      assert(fixture.state.nativeChineseReadBack && fixture.state.nativeSubmissionReadBack, 'Native model actions did not produce verified AX observations')
      const submitted = JSON.parse(readFileSync(nativeFixture.statePath, 'utf8'))
      assert(submitted.pid === nativeFixture.pid && submitted.submissions === 1 && submitted.currentValue === chinese && submitted.submittedValue === chinese,
        'Independent AppKit state file did not confirm exactly one Chinese submission')
      report.appkitFixture.submitted = submitted
      report.appkitFixture.independentStateVerified = true
      report.appkitFixture.screenshotCount = 0
      report.appkitFixture.modelMethods = fixture.state.nativeModelMethods
    }
    report.hostExit = await host.stop()
    host = undefined
    assert(report.hostExit.code === 0, 'Packaged Host exited unsuccessfully')
    if (nativeFixture) {
      report.appkitFixture.exit = await nativeFixture.stop()
      nativeFixture = undefined
    }
    if (input.gui) report.gui = await smokeGui(executable, env, home, fixture.origin)
    }
    report.status = 'passed'
    passed = true
  } catch (error) {
    report.error = error instanceof Error ? error.stack : String(error)
    if (host) report.hostLogTail = host.tail()
    process.exitCode = 1
  } finally {
    if (host) {
      try { report.hostExit = await host.stop() } catch (error) { report.cleanupError = String(error); passed = false; report.status = 'failed'; process.exitCode = 1 }
    }
    if (nativeFixture) {
      try { report.appkitFixture = { ...report.appkitFixture, exit: await nativeFixture.stop() } }
      catch (error) { report.cleanupError = String(error); passed = false; report.status = 'failed'; process.exitCode = 1 }
    }
    await fixture?.close()
    report.localModelRequests = fixture?.state.requests ?? 0
    clearInterval(processTracker)
    report.processCleanup = await cleanupChildren(children)
    if (report.processCleanup.stillActive.length || report.processCleanup.forcedCleanup.length) {
      report.status = 'failed'; report.cleanupError = 'Owned descendants required forced cleanup or remained after shutdown'; passed = false; process.exitCode = 1
    }
    if (passed) { await rm(home, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 }); report.isolatedHomeRemoved = true }
    else report.isolatedHomeRemoved = false
    await mkdir(dirname(input.report), { recursive: true })
    await writeFile(input.report, JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  }
  console.log(`Packaged Browser/Computer smoke ${report.status}. Report: ${input.report}`)
  if (!passed) console.error(report.error ?? report.cleanupError)
}

if (process.argv[2] === '--inspect-runtime') {
  console.log(marker + JSON.stringify(await inspectRuntime(process.argv[3])))
} else if (process.argv[2] === '--initialize-profile') {
  console.log(marker + JSON.stringify(await initializeProfile(process.argv[3], process.argv[4], process.argv[5])))
} else {
  await main(options(process.argv.slice(2)))
}
