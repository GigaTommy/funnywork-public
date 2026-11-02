/** Real `dsh web` authentication against a temporary Harness home. */

import type { ChildProcess } from 'node:child_process'
import { spawn } from 'node:child_process'
import { stat } from 'node:fs/promises'
import { request as httpRequest } from 'node:http'
import { createRequire } from 'node:module'
import { createServer } from 'node:net'
import type { AddressInfo } from 'node:net'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { describe, expect, it, vi } from 'vitest'
import { historyHost } from '../../desktop/src/funnyworker-history-sync.ts'

const REPO_ROOT = fileURLToPath(new URL('../../..', import.meta.url))
const DSH_SOURCE_BIN = join(REPO_ROOT, 'apps/cli/src/bin.ts')
const TSX_LOADER = pathToFileURL(createRequire(join(REPO_ROOT, 'package.json')).resolve('tsx/esm')).href

interface RunningWeb {
  readonly child: ChildProcess
  readonly launchUrl: string
  readonly output: () => string
}

interface HttpResult {
  readonly status: number
  readonly body: string
}

function redact(output: string): string {
  return output.replace(/([?&]token=)[^\s)]+/gu, '$1<redacted>')
}

/** Reserve one concrete loopback port, then release it for the CLI process. */
async function freePort(): Promise<number> {
  const server = createServer()
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const port = (server.address() as AddressInfo).port
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error === undefined) resolve()
      else reject(error)
    })
  })
  return port
}

function cleanEnvironment(root: string, dshHome: string): NodeJS.ProcessEnv {
  const env = Object.fromEntries(Object.entries(process.env).filter(([name]) =>
    !/(?:KEY|SECRET|TOKEN|PASSWORD)/iu.test(name)))
  return {
    ...env,
    DSH_AGENTS_HOME: join(root, '.agents'),
    DSH_HOME: dshHome,
    DSH_TELEMETRY_DISABLED: '1',
    NODE_NO_WARNINGS: '1',
    SSH_CONNECTION: '',
    SSH_TTY: '',
    TSX_TSCONFIG_PATH: join(REPO_ROOT, 'tsconfig.json'),
  }
}

/** Start the public source CLI and wait for its authenticated readiness URL. */
async function startWeb(root: string, dshHome: string, port: number, patch?: string): Promise<RunningWeb> {
  const child = spawn(process.execPath, [
    ...(patch === undefined ? ['--import', TSX_LOADER, DSH_SOURCE_BIN] : [join(REPO_ROOT, 'apps/cli/lib/bin.js')]),
    ...(patch === undefined ? ['web'] : ['--profile', 'web', '--patch', patch]),
    '--no-open',
    '--port', String(port),
  ], {
    cwd: root,
    env: cleanEnvironment(root, dshHome),
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let output = ''
  const launchUrl = await new Promise<string>((resolve, reject) => {
    let settled = false
    const fail = (error: Error): void => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(error)
    }
    const timer = setTimeout(() => {
      fail(new Error(`dsh web did not become ready:\n${redact(output)}`))
    }, 90_000)
    const append = (chunk: Buffer | string): void => {
      output = `${output}${String(chunk)}`.slice(-100_000)
      const match = /dsh web: (http:\/\/[^\s]+)/u.exec(output)
      if (settled || match?.[1] === undefined) return
      settled = true
      clearTimeout(timer)
      resolve(match[1])
    }
    child.stdout?.on('data', append)
    child.stderr?.on('data', append)
    child.once('error', (error) => {
      fail(error)
    })
    child.once('exit', (code) => {
      fail(new Error(`dsh web exited before readiness (${String(code)}):\n${redact(output)}`))
    })
  })
  return { child, launchUrl, output: () => output }
}

async function stopWeb(running: RunningWeb): Promise<void> {
  if (running.child.exitCode !== null) return
  const exited = new Promise<void>((resolve) => { running.child.once('exit', () => { resolve() }) })
  running.child.kill('SIGTERM')
  const forced = setTimeout(() => { running.child.kill('SIGKILL') }, 10_000)
  forced.unref()
  await exited
  clearTimeout(forced)
}

/** POST one real Remote envelope while controlling the wire Host header. */
function describeSettings(port: number, host: string, cookie?: string): Promise<HttpResult> {
  const body = JSON.stringify({
    type: 'client-request',
    rpcId: 'web-auth-real-cli',
    method: 'settings/describe',
    payload: { args: {} },
  })
  return new Promise((resolve, reject) => {
    const req = httpRequest({
      hostname: '127.0.0.1',
      port,
      path: '/api/settings/describe',
      method: 'POST',
      headers: {
        host,
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        ...cookie === undefined ? {} : { cookie },
      },
    }, (res) => {
      const chunks: Uint8Array[] = []
      res.on('data', (chunk: Buffer) => { chunks.push(chunk) })
      res.on('end', () => {
        resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') })
      })
    })
    req.once('error', reject)
    req.end(body)
  })
}

describe('dsh web authentication through the real CLI', () => {
  it('replicates a cold native log without activating an Agent or persisting synthetic closers', { timeout: 120_000 }, async () => {
    const root = await mkdtemp(join(tmpdir(), 'history-real-cli-'))
    let running: RunningWeb | undefined
    try {
      const patch = join(root, 'history.patch.yml')
      await writeFile(patch, JSON.stringify([{ insert: [{ id: 'history-fixture', name: new URL('./fixtures/history-sync/register.mjs', import.meta.url).href }] }]))
      running = await startWeb(root, join(root, 'home'), await freePort(), patch)
      const exchange = await fetch(running.launchUrl, { redirect: 'manual' })
      const cookie = exchange.headers.get('set-cookie')?.split(';', 1)[0]
      if (cookie === undefined) throw new Error('Missing test Host cookie')
      const origin = new URL(running.launchUrl).origin
      const rpc = async (method: string, args: object): Promise<unknown> => {
        const response = await fetch(`${origin}/api/session/${method}`, { method: 'POST', headers: { cookie, origin, 'content-type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: 'history-test', method: `session/${method}`, payload: { args } }) })
        expect(response.status).toBe(200)
        const envelope = await response.json() as { result: { ok: boolean; value: unknown } }
        expect(envelope.result.ok, JSON.stringify(envelope)).toBe(true)
        return envelope.result.value
      }
      const replica = historyHost(running.launchUrl, cookie)
      expect(await replica.invoke('modelCatalog', {})).toHaveProperty('groups')
      expect(await replica.invoke('presets', {})).toHaveProperty('presets')
      await vi.waitFor(async () => { expect(await replica.choices?.()).toHaveProperty('models') })
      expect(await replica.invoke('replicaList', {})).toHaveProperty('history-sync-cold')
      expect(await replica.invoke('replicaPage', { id: 'history-sync-cold', offset: 0 })).toMatchObject({ nextSeq: 1, inheritedEventCount: 0, events: [{ type: 'turn/start', seq: 0 }] })
      const list = await rpc('list', { _request: {} }) as { items: { sessionId: string; agentAvailable: boolean }[] }
      expect(list.items.some(item => item.sessionId === 'history-sync-cold' && item.agentAvailable)).toBe(false)
      expect(await replica.invoke('replicaPage', { id: 'history-sync-cold', offset: 1 })).toMatchObject({ nextSeq: 1, events: [] })
      const filePage = await rpc('replicaPage', { request: { id: 'history-sync-file', offset: 0 } }) as { events: { type: string; data: { content?: { attachment?: { attachmentId: string } }[] } }[] }
      const attachment = filePage.events.find(event => event.type === 'user/message')?.data.content?.[0]?.attachment
      expect(await replica.invoke('replicaAttachmentChunk', { sessionId: 'history-sync-file', attachmentId: attachment?.attachmentId, offset: 0 })).toEqual({ data: Buffer.from('fixture attachment').toString('base64'), nextOffset: 18 })
      await expect(replica.invoke('replicaAttachmentChunk', { sessionId: 'history-sync-cold', attachmentId: attachment?.attachmentId, offset: 0 })).rejects.toThrow()
      expect(running.output()).not.toContain('history-sync-unexpected-activation')
      replica.pending?.()
      await vi.waitFor(() => { expect(replica.ready?.()).toBe(true) }, { timeout: 10000 })
      const workspaceResponse = await fetch(`${origin}/api/workspace/create`, { method: 'POST', headers: { cookie, origin, 'content-type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: 'history-workspace', method: 'workspace/create', payload: { args: { request: { path: root } } } }) })
      const workspaceEnvelope = await workspaceResponse.json() as { result: { ok: boolean; value: { workspace: { workspaceId: string } } } }
      expect(workspaceEnvelope.result.ok).toBe(true)
      const workspaceId = workspaceEnvelope.result.value.workspace.workspaceId
      await vi.waitFor(() => {
        expect(replica.workspaces?.()?.some(workspace => workspace.id === workspaceId)).toBe(true)
      }, { timeout: 10000 })
      expect(await replica.invoke('create', { sessionId: 'mobile-test-create', workspaceId })).toMatchObject({ sessionId: 'mobile-test-create' })
      const mobileCatalog = await replica.invoke('modelCatalog', {}) as { groups: { id: string; models: { id: string }[] }[] }
      const mobileProvider = mobileCatalog.groups.find(group => group.models.length > 0)
      expect(mobileProvider).toBeDefined()
      const mobileSelection = { sessionId: 'mobile-test-create', provider: mobileProvider!.id,
        model: mobileProvider!.models[0]!.id, saveDefault: false }
      expect(await replica.invoke('selectModel', mobileSelection)).toMatchObject({ selected: {
        provider: mobileSelection.provider, model: mobileSelection.model,
      } })
      await replica.invoke('replicaFlush', {})
      const createdPage = await replica.invoke('replicaPage', { id: 'mobile-test-create', offset: 0 }) as {
        header: { id: string }
        events: { type: string }[]
      }
      expect({ sessionId: createdPage.header.id, workEvents: createdPage.events.filter(event => event.type === 'user/message' || event.type === 'turn/start') }).toMatchInlineSnapshot(`
        {
          "sessionId": "mobile-test-create",
          "workEvents": [],
        }
      `)
      expect(await replica.invoke('create', { sessionId: 'mobile-test-create', workspaceId })).toMatchObject({ sessionId: 'mobile-test-create' })
      if (replica.upload === undefined) throw new Error('Missing native file upload')
      const uploaded = await replica.upload('mobile-test-create', 'phone.txt', Buffer.from('phone file'))
      expect(uploaded.bytes).toBe(10)
      await replica.invoke('prompt', { sessionId: 'mobile-test-create', requestId: 'phone-file-command', mode: 'queue', content: [{ type: 'text', text: 'Inspect the attached file' }, { type: 'file', receiptId: uploaded.receiptId }] })
      await replica.invoke('replicaFlush', {})
      const uploadedPage = await replica.invoke('replicaPage', { id: 'mobile-test-create', offset: 0 })
      expect(JSON.stringify(uploadedPage)).toContain(uploaded.attachmentId)
      expect(JSON.stringify(uploadedPage)).toContain('phone.txt')
      const nativeChunk = await replica.invoke('replicaAttachmentChunk', { sessionId: 'mobile-test-create', attachmentId: uploaded.attachmentId, offset: 0 })
      expect(nativeChunk).toMatchInlineSnapshot(`
        {
          "data": "cGhvbmUgZmlsZQ==",
          "nextOffset": 10,
        }
      `)
      expect(await rpc('create', { request: { sessionId: 'history-sync-approval' } })).toMatchObject({ sessionId: 'history-sync-approval' })
      expect(await replica.invoke('create', { sessionId: 'mobile-live', workspaceId })).toMatchObject({ sessionId: 'mobile-live' })
      await replica.invoke('prompt', { sessionId: 'mobile-live', requestId: 'live-fixture', mode: 'queue', content: [{ type: 'text', text: '[mobile-live-fixture]' }] })
      await vi.waitFor(async () => {
        expect(await replica.invoke('replicaState', {})).toMatchObject({ 'mobile-live': { running: true, liveAssistant: { text: 'Live fixture reply', truncated: false } } })
      }, { timeout: 15000 })
      const beforeSettlement = await replica.invoke('replicaPage', { id: 'mobile-live', offset: 0 })
      expect(JSON.stringify(beforeSettlement)).not.toContain('Live fixture reply')
      await fetch(`${origin}/api/history-fixture/live-release`, { method: 'POST', headers: { cookie, origin }, body: '{}' })
      await vi.waitFor(async () => {
        const state = await replica.invoke('replicaState', {}) as Record<string, { running: boolean; liveAssistant?: object }>
        expect(state['mobile-live']?.liveAssistant).toBeUndefined()
        expect(JSON.stringify(await replica.invoke('replicaPage', { id: 'mobile-live', offset: 0 }))).toContain('Live fixture reply')
      }, { timeout: 15000 })
      await replica.invoke('create', { sessionId: 'mobile-live-cancel', workspaceId })
      await replica.invoke('prompt', { sessionId: 'mobile-live-cancel', requestId: 'live-cancel-fixture', mode: 'queue', content: [{ type: 'text', text: '[mobile-live-fixture]' }] })
      await vi.waitFor(async () => {
        expect(await replica.invoke('replicaState', {})).toMatchObject({ 'mobile-live-cancel': { liveAssistant: { text: 'Live fixture reply' } } })
      }, { timeout: 15000 })
      const cancelState = await replica.invoke('replicaState', {}) as Record<string, { turnStart: number }>
      await replica.invoke('cancel', { sessionId: 'mobile-live-cancel', expectedTurn: cancelState['mobile-live-cancel']?.turnStart })
      await vi.waitFor(async () => {
        const page = await replica.invoke('replicaPage', { id: 'mobile-live-cancel', offset: 0 }) as { events: { type: string; data: object }[] }
        expect(page.events.find(event => event.type === 'assistant/message')?.data).toMatchObject({
          interrupted: true, message: { content: [{ type: 'text', text: 'Live fixture reply' }] },
        })
        const state = await replica.invoke('replicaState', {}) as Record<string, { liveAssistant?: object }>
        expect(state['mobile-live-cancel']?.liveAssistant).toBeUndefined()
      }, { timeout: 15000 })
      const waiting = fetch(new URL('/api/history-fixture/ask', running.launchUrl), { method: 'POST', headers: { cookie, origin: new URL(running.launchUrl).origin }, body: '{}' })
      let early = ''
      void waiting.then(async (response) => { early = `${response.status}:${await response.clone().text()}` })
      try {
        try { await vi.waitFor(() => { expect(early, early).toBe(''); expect(replica.pending?.()).toHaveLength(1) }, { timeout: 10000 }) }
        catch (error) { throw new Error(`${String(error)}\n${redact(running.output())}`) }
        const pending = replica.pending?.()[0]
        expect(pending).toMatchObject({ event: 'approval/request', agentId: 'history-sync-approval' })
        await replica.invoke('decision', { eventId: pending?.eventId, outcome: { kind: 'result', value: 'allowed-once' } })
        expect(await (await waiting).json()).toEqual({ outcome: 'allowed-once' })
        await vi.waitFor(() => { expect(replica.pending?.()).toHaveLength(0) })
        expect(JSON.stringify(await replica.invoke('replicaPage', { id: 'history-sync-approval', offset: 0 }))).toContain('\"type\":\"approval/decided\"')
        const local = historyHost(running.launchUrl, cookie)
        local.pending?.()
        try {
          await vi.waitFor(() => { expect(local.ready?.()).toBe(true) })
          const second = fetch(new URL('/api/history-fixture/ask', running.launchUrl), { method: 'POST', headers: { cookie, origin: new URL(running.launchUrl).origin }, body: '{}' })
          await vi.waitFor(() => { expect(local.pending?.()).toHaveLength(1) })
          await vi.waitFor(() => { expect(replica.pending?.()).toHaveLength(1) })
          const eventId = local.pending?.()[0]?.eventId
          await local.invoke('decision', { eventId, outcome: { kind: 'result', value: 'rejected' } })
          expect(await (await second).json()).toEqual({ outcome: 'rejected' })
          await vi.waitFor(() => { expect(replica.pending?.()).toHaveLength(0) })
          await replica.invoke('decision', { eventId, outcome: { kind: 'result', value: 'allowed-once' } })
          const closed = await replica.invoke('replicaPage', { id: 'history-sync-approval', offset: 0 }) as { events: { type: string; data: { outcome?: string } }[] }
          expect(closed.events.filter(event => event.type === 'approval/decided').map(event => event.data.outcome)).toEqual(['allowed-once', 'rejected'])
        } finally { local.dispose?.() }
        const question = fetch(new URL('/api/history-fixture/question', running.launchUrl), { method: 'POST', headers: { cookie, origin: new URL(running.launchUrl).origin }, body: '{}' })
        await vi.waitFor(() => { expect(replica.pending?.()).toHaveLength(1) })
        const questionEvent = replica.pending?.()[0]
        expect(questionEvent).toMatchObject({ event: 'user-questions/request', agentId: 'history-sync-approval' })
        const answer = { answers: [{ id: 'topic', selected: ['记录'], custom: '' }] }
        await replica.invoke('decision', { eventId: questionEvent?.eventId, outcome: { kind: 'result', value: answer } })
        expect(await (await question).json()).toEqual(answer)
        expect(replica.pending?.()).toHaveLength(0)
      } finally { replica.dispose?.() }
    } finally {
      if (running !== undefined) await stopWeb(running)
      await rm(root, { recursive: true, force: true })
    }
  })
  it('rejects a forged loopback Host and preserves the browser cookie across restart', { timeout: 180_000 }, async () => {
    const root = await mkdtemp(join(tmpdir(), 'dsh-web-auth-real-cli-'))
    const dshHome = join(root, '.dsh')
    const port = await freePort()
    let first: RunningWeb | undefined
    let second: RunningWeb | undefined
    try {
      first = await startWeb(root, dshHome, port)
      const firstUrl = new URL(first.launchUrl)
      expect(firstUrl.origin).toBe(`http://127.0.0.1:${String(port)}`)
      expect(firstUrl.pathname).toBe('/')
      expect(firstUrl.searchParams.get('token')).toMatch(/^[A-Za-z0-9_-]{43}$/u)

      expect(await describeSettings(port, `localhost:${String(port)}`)).toEqual({
        status: 401,
        body: 'unauthorized',
      })

      const exchange = await fetch(first.launchUrl, { redirect: 'manual' })
      expect(exchange.status).toBe(303)
      expect(exchange.headers.get('location')).toBe('./')
      const setCookie = exchange.headers.get('set-cookie')
      if (setCookie === null) throw new Error('real CLI token exchange omitted Set-Cookie')
      expect(setCookie).toContain('HttpOnly')
      expect(setCookie).toContain('SameSite=Strict')
      expect(setCookie).not.toContain('Secure')
      const cookie = setCookie.split(';', 1)[0]!

      const authenticated = await describeSettings(port, firstUrl.host, cookie)
      expect(authenticated.status).toBe(200)
      const authenticatedBody: unknown = JSON.parse(authenticated.body)
      expect(authenticatedBody).toMatchObject({
        type: 'server-response',
        rpcId: 'web-auth-real-cli',
        result: { ok: true, value: { namespaces: expect.any(Array) as unknown } },
      })

      await stopWeb(first)
      first = undefined
      second = await startWeb(root, dshHome, port)
      const secondUrl = new URL(second.launchUrl)
      expect(secondUrl.searchParams.get('token')).not.toBe(firstUrl.searchParams.get('token'))
      expect((await describeSettings(port, secondUrl.host, cookie)).status).toBe(200)

      const credentialMode = (await stat(join(dshHome, '.credentials.yaml'))).mode & 0o777
      expect(credentialMode).toBe(0o600)
    } catch (error) {
      const evidence = [first?.output(), second?.output()].filter(value => value !== undefined).join('\n')
      throw new Error(`${error instanceof Error ? error.message : String(error)}\n${redact(evidence)}`, { cause: error })
    } finally {
      if (second !== undefined) await stopWeb(second)
      if (first !== undefined) await stopWeb(first)
      await rm(root, { recursive: true, force: true })
    }
  })
})
