/** Real Chromium and pinned MCP checks against disposable loopback pages, without model requests. */
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { Context } from '@deepseek-ai/cordis'
import BrowserUse from '@deepseek-ai/dsh-browser-use'
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '@deepseek-ai/dsh-agent-loop-testkit'
import { SessionId } from '@deepseek-ai/dsh-session'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import type { Agent } from '@deepseek-ai/dsh-agent'
import type { ToolExecutionResult } from '@deepseek-ai/dsh-tools'
import * as Provider from '@deepseek-ai/dsh-experimental-browser-use-playwright-mcp'
import { expect, it, vi } from 'vitest'
import { resolveBrowserExecutable } from '../src/browser-executable.ts'

const executable = process.env.DSH_BROWSER_EXECUTABLE ?? resolveBrowserExecutable({ searchPaths: [] })
const visible = process.env.DSH_BROWSER_VISIBLE === '1'

function processParents(): Map<number, { parent: number; state: string }> {
  const output = process.platform === 'win32'
    ? execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      'Get-CimInstance Win32_Process | ForEach-Object { "$($_.ProcessId) $($_.ParentProcessId) R" }',
    ], { encoding: 'utf8', windowsHide: true, timeout: 15000 })
    : execFileSync('/bin/ps', ['-eo', 'pid=,ppid=,stat='], { encoding: 'utf8' })
  return new Map(output.trim().split('\n').map((line) => {
    const [pid, parent, state] = line.trim().split(/\s+/u)
    return [Number(pid), { parent: Number(parent), state: state! }]
  }))
}

function descendants(): Set<number> {
  const processes = processParents()
  const children = new Set<number>()
  let frontier = [process.pid]
  while (frontier.length > 0) {
    const parents = new Set(frontier)
    frontier = [...processes].filter(([pid, details]) => parents.has(details.parent) && !children.has(pid)).map(([pid]) => pid)
    for (const pid of frontier) children.add(pid)
  }
  return children
}

function textOf(result: ToolExecutionResult): string {
  expect(result.isError, JSON.stringify(result.content)).toBeFalsy()
  expect(result.content.every(block => block.type === 'text')).toBe(true)
  if (!result.isError && typeof result.value === 'object' && result.value !== null && !Array.isArray(result.value)) {
    const content = result.value.content
    expect(Array.isArray(content)).toBe(true)
    if (Array.isArray(content)) {
      expect(content.every(block => typeof block === 'object' && block !== null && !Array.isArray(block) && block.type === 'text')).toBe(true)
    }
  }
  return result.content.flatMap(block => block.type === 'text' ? [block.text] : []).join('\n')
}

it.skipIf(executable === undefined)('uses text snapshots to fill a real form and isolates login state between live Sessions', async () => {
  const root = await mkdtemp(join(tmpdir(), 'funnywork-browser-dom-'))
  const server = createServer((request, response) => {
    if (request.url === '/seed') response.setHeader('Set-Cookie', 'owner=first; Path=/; SameSite=Lax')
    response.setHeader('Content-Type', 'text/html; charset=utf-8')
    response.end(`<!doctype html><title>FunnyWork browser fixture</title><h1>Browser form</h1>
      <p>Cookie: ${request.headers.cookie ?? 'none'}</p>
      <label>Employee name <input id="employee"></label><button id="save">Save</button>
      <p role="status" id="result">Not saved</p>
      <script>document.getElementById('save').onclick=()=>{document.getElementById('result').textContent='Saved '+document.getElementById('employee').value}</script>`)
  })
  const ctx = new Context()
  let calls = 0
  let verified = false
  let observedProcesses = 0
  const call = async (agent: Agent, name: string, args: Record<string, unknown> = {}) => textOf(await ctx.tools.execute({
    agent, name: `mcp__playwright-mcp__${name}`, arguments: args,
    callId: ToolCallId(`browser-fixture-${++calls}`), signal: new AbortController().signal,
  }))
  try {
    server.listen(0, '127.0.0.1')
    await once(server, 'listening')
    const address = server.address()
    if (address === null || typeof address === 'string') throw new Error('Browser fixture has no loopback listener')
    const origin = `http://127.0.0.1:${address.port}`
    await mountAgentLoopTestDependencies(ctx, { workingDirectory: true })
    await mountAgentLoopTestHarness(ctx)
    await ctx.plugin(BrowserUse)
    await ctx.plugin(Provider, { mode: 'launch', executablePath: executable!, headless: !visible, imageResponses: 'omit' })
    const first = await ctx.agents.create({ sessionId: SessionId('browser-dom-first'), meta: { cwd: root } })
    await call(first.agent, 'browser_navigate', { url: `${origin}/seed` })
    const snapshot = await call(first.agent, 'browser_snapshot')
    const field = snapshot.match(/textbox "Employee name" \[ref=([^\]]+)\]/u)?.[1]
    const button = snapshot.match(/button "Save" \[ref=([^\]]+)\]/u)?.[1]
    expect(field, snapshot).toBeDefined()
    expect(button, snapshot).toBeDefined()
    await call(first.agent, 'browser_fill_form', { fields: [{ name: 'Employee name', type: 'textbox', target: field!, value: 'Ada' }] })
    await call(first.agent, 'browser_click', { target: button!, element: 'Save' })
    expect(await call(first.agent, 'browser_snapshot')).toContain('Saved Ada')
    await call(first.agent, 'browser_take_screenshot', { filename: 'omitted-image-response.png' })
    await call(first.agent, 'browser_navigate', { url: origin })
    expect(await call(first.agent, 'browser_snapshot')).toContain('Cookie: owner=first')

    const second = await ctx.agents.create({ sessionId: SessionId('browser-dom-second'), meta: { cwd: root } })
    await call(second.agent, 'browser_navigate', { url: origin })
    expect(await call(second.agent, 'browser_snapshot')).toContain('Cookie: none')
    const ownedProcesses = descendants()
    observedProcesses = ownedProcesses.size
    await first.dispose()
    expect(ctx.tools.schemas(first.agent)).toEqual([])
    expect(await call(second.agent, 'browser_snapshot')).toContain('Browser form')
    await second.dispose()
    expect(ctx.tools.schemas(second.agent)).toEqual([])
    await vi.waitFor(() => {
      const processes = processParents()
      expect([...ownedProcesses].filter(pid => processes.has(pid) && !processes.get(pid)!.state.startsWith('Z'))).toEqual([])
    }, { timeout: 15000 })
    verified = true
  } finally {
    await ctx.fiber.dispose()
    server.closeAllConnections()
    await new Promise<void>((resolve, reject) => server.close((error) => {
      if (error === undefined) resolve()
      else reject(error)
    }))
    await rm(root, { recursive: true, force: true })
  }
  const reportPath = process.env.DSH_BROWSER_REPORT_PATH
  if (reportPath !== undefined) {
    if (!isAbsolute(reportPath)) throw new Error('DSH_BROWSER_REPORT_PATH must be absolute')
    await writeFile(reportPath, `${JSON.stringify({
      status: verified ? 'passed' : 'failed', timestamp: new Date().toISOString(),
      driver: '@playwright/mcp@0.0.80', browserExecutable: executable, visible,
      observations: 'accessibility-dom', imageResponses: 'omit', modelRequests: 0,
      checks: { formSaved: true, loginStateIsolated: true, imagesOmittedOnWire: true,
        sessionToolsRemoved: true, ownedProcessesStopped: observedProcesses, temporaryFilesRemoved: true },
    }, null, 2)}\n`, { mode: 0o600, flag: 'wx' })
  }
})
