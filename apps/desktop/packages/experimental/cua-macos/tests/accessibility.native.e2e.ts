/** Opt-in real AppKit AX read, Chinese value assignment, submit, clear, and canceled-input checks. */

import { execFile, spawn, type ChildProcess } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { Context } from '@deepseek-ai/cordis'
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '@deepseek-ai/dsh-agent-loop-testkit'
import ComputerUseRegistry from '@deepseek-ai/dsh-computer-use'
import { LlmAdapter, ToolCallId, type LlmResolvedModelInfo, type StreamChunk } from '@deepseek-ai/dsh-llm'
import { SessionId } from '@deepseek-ai/dsh-session'
import SubprocessLocal from '@deepseek-ai/dsh-subprocess-local'
import UserQuestions from '@deepseek-ai/dsh-user-questions'
import { expect, it } from 'vitest'
import { z } from 'zod'
import * as NativeProvider from '../src/index.ts'

const run = promisify(execFile)
const State = z.object({ appHandle: z.string(), pid: z.number(), windowId: z.number().nullable(),
  snapshotId: z.string(), tree: z.string() })
const FixtureState = z.object({ submissions: z.number(), submittedValue: z.string(), currentValue: z.string() })

class TextModel extends LlmAdapter {
  requests = 0
  override resolveModel(provider: string, model: string): Promise<LlmResolvedModelInfo> {
    return Promise.resolve({ provider, id: model, name: model, inputModalities: ['text'] })
  }
  stream(): AsyncIterable<StreamChunk> { this.requests += 1; throw new Error('This native acceptance test sends no model request') }
}

function element(tree: string, label: string): number {
  const matches = tree.split('\n').filter(line => line.includes(label)).map(line => line.match(/\[(\d+)\]/)?.[1]).filter(value => value !== undefined)
  expect(matches, `one indexed fixture element for ${label}`).toHaveLength(1)
  return Number(matches[0])
}

async function stopFixture(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return
  const exited = once(child, 'exit')
  child.kill('SIGTERM')
  await exited
}

it.skipIf(process.platform !== 'darwin' || process.env.DSH_CUA_AX_E2E !== '1')(
  'operates only its task-owned AppKit fixture through AX text and never captures the screen',
  { timeout: 120_000, retry: 0 },
  async () => {
    const artifactBase = process.env.AGENT_APPS_DIR
    if (artifactBase === undefined) throw new Error('Run this test with with-external-storage so its application, compiler cache, and evidence remain on the external volume')
    const artifactRoot = await mkdtemp(join(artifactBase, 'computer-use-ax-'))
    const app = join(artifactRoot, 'FunnyWorkAXFixture.app')
    const executable = join(app, 'Contents/MacOS/FunnyWorkAXFixture')
    const statePath = join(artifactRoot, 'fixture-state.json')
    const reportPath = join(artifactRoot, 'acceptance.json')
    const moduleCache = join(artifactRoot, 'module-cache')
    const helperPath = process.env.DSH_CUA_AX_HELPER ?? resolve(fileURLToPath(new URL('../native/bin', import.meta.url)), `darwin-${process.arch}`, 'dsh-cua-native')
    const evidence: Record<string, unknown> = { status: 'running', screenshotRequested: false, modelInput: ['text'], helperPath, fixtureApp: app, steps: [] }
    const textModel = new TextModel()
    const steps: string[] = []
    evidence.steps = steps
    const ctx = new Context()
    let fixture: ChildProcess | undefined
    try {
      await mkdir(dirname(executable), { recursive: true })
      await mkdir(moduleCache, { recursive: true })
      await writeFile(join(app, 'Contents/Info.plist'), '<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>com.funnywork.tests.ax-fixture</string><key>CFBundleName</key><string>FunnyWork AX Fixture</string><key>CFBundleExecutable</key><string>FunnyWorkAXFixture</string><key>CFBundlePackageType</key><string>APPL</string><key>LSMinimumSystemVersion</key><string>14.0</string></dict></plist>')
      await run('/usr/bin/xcrun', ['swiftc', fileURLToPath(new URL('./fixtures/AXTextFixture.swift', import.meta.url)),
        '-module-cache-path', moduleCache, '-o', executable], {
        env: { ...process.env, CLANG_MODULE_CACHE_PATH: moduleCache,
          SWIFT_MODULECACHE_PATH: moduleCache, SWIFTPM_MODULECACHE_OVERRIDE: moduleCache },
      })
      await run('/usr/bin/codesign', ['--force', '--sign', '-', app])
      fixture = spawn(executable, [statePath], { stdio: ['ignore', 'pipe', 'pipe'] })
      await new Promise<void>((resolveReady, reject) => {
        fixture!.once('error', reject)
        fixture!.once('exit', (code) => { reject(new Error(`Fixture exited before readiness: ${String(code)}`)) })
        fixture!.stdout!.on('data', (chunk) => { if (String(chunk).includes('READY')) resolveReady() })
      })
      steps.push('Compiled and launched only the task-owned AppKit fixture')
      await mountAgentLoopTestDependencies(ctx)
      ctx.llm.registerAdapter(['ax-text-fixture'], textModel)
      await ctx.plugin(ComputerUseRegistry)
      await ctx.plugin(SubprocessLocal)
      await ctx.plugin(UserQuestions)
      ctx.on('user-questions/request', async () => ({ answers: [{ id: 'cua-app-access', selected: ['Allow for this task'] }] }))
      await ctx.plugin(NativeProvider, { helperPath, mode: 'text', unavailablePolicy: 'error' })
      const harness = await mountAgentLoopTestHarness(ctx)
      const agent = await harness.create(SessionId('real-ax-fixture'), { provider: 'ax-text-fixture', model: 'text-only' })
      let call = 0
      const execute = async (method: string, params: Record<string, unknown>, signal = new AbortController().signal) => {
        const result = await ctx.tools.execute({ agent, name: 'cua', callId: ToolCallId(`ax-real-${++call}`), arguments: { method, params }, signal })
        expect(result.isError, JSON.stringify(result.content)).toBe(false)
        if (result.isError) throw new Error(JSON.stringify(result.content))
        expect(result.content.some(block => block.type === 'image')).toBe(false)
        return result.value
      }
      const capabilities = z.object({ permissions: z.object({ accessibility: z.boolean(), screenRecording: z.boolean() }) }).parse(await execute('getCapabilities', {}))
      evidence.permissions = capabilities.permissions
      if (!capabilities.permissions.accessibility) throw new Error('Accessibility permission is missing for the launching host. Grant it in macOS System Settings and rerun; this test never requests or bypasses it.')
      const selected = z.object({ appHandle: z.string(), pid: z.number() }).parse(await execute('getApp', { selector: app }))
      expect(selected.pid).toBe(fixture.pid)
      let state = State.parse(await execute('getState', { appHandle: selected.appHandle, details: true }))
      steps.push('Read the real AX tree with a text-only model route and no screenshot')
      const chinese = '中文输入已验证'
      await execute('setValue', { appHandle: selected.appHandle, snapshotId: state.snapshotId, elementIndex: element(state.tree, 'Fixture editable input'), value: chinese })
      state = State.parse(await execute('getState', { appHandle: selected.appHandle }))
      expect(state.tree).toContain(chinese)
      steps.push('Assigned and read back Chinese text through AX')
      await execute('click', { appHandle: selected.appHandle, snapshotId: state.snapshotId, elementIndex: element(state.tree, 'Submit fixture value') })
      state = State.parse(await execute('getState', { appHandle: selected.appHandle }))
      expect(state.tree).toContain(`Result: ${chinese}`)
      const submitted = FixtureState.parse(JSON.parse(await readFile(statePath, 'utf8')))
      expect(submitted.submittedValue).toBe(chinese)
      expect(submitted.submissions).toBe(1)
      steps.push('Invoked the real AX submit action and independently verified the fixture state file')
      await execute('setValue', { appHandle: selected.appHandle, snapshotId: state.snapshotId, elementIndex: element(state.tree, 'Fixture editable input'), value: '' })
      state = State.parse(await execute('getState', { appHandle: selected.appHandle }))
      expect(state.tree.split('\n').find(line => line.includes('Fixture editable input'))).toContain('value=""')
      steps.push('Cleared the editable AX value and read back an empty string')
      const cancel = new AbortController()
      cancel.abort()
      const canceled = await ctx.tools.execute({ agent, name: 'cua', callId: ToolCallId(`ax-real-${++call}`), signal: cancel.signal,
        arguments: { method: 'setValue', params: { appHandle: selected.appHandle, snapshotId: state.snapshotId, elementIndex: element(state.tree, 'Fixture editable input'), value: 'must not be entered' } } })
      expect(canceled.isError).toBe(true)
      state = State.parse(await execute('getState', { appHandle: selected.appHandle }))
      expect(state.tree).not.toContain('must not be entered')
      expect(FixtureState.parse(JSON.parse(await readFile(statePath, 'utf8'))).submissions).toBe(1)
      steps.push('Canceled input before dispatch and verified no fixture mutation')
      evidence.status = 'passed'
      evidence.screenshotCount = 0
      expect(textModel.requests).toBe(0)
      evidence.hostPermissionScope = 'This launching test host; the packaged FunnyWork app requires its own OS grant.'
    } catch (error) {
      evidence.status = 'failed'
      evidence.error = error instanceof Error ? error.message : String(error)
      throw error
    } finally {
      try { await ctx.fiber.dispose() } finally {
        if (fixture !== undefined) await stopFixture(fixture)
        evidence.modelRequests = textModel.requests
        if (evidence.status === 'passed') await rm(moduleCache, { recursive: true, force: true })
        await writeFile(reportPath, `${JSON.stringify(evidence, null, 2)}\n`)
        process.stdout.write(`Computer Use AX acceptance evidence: ${reportPath}\n`)
      }
    }
  },
)
