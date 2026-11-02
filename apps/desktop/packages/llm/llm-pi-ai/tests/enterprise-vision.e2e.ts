/** Real Flash vision through a local gateway, using an employee's scoped entry credential. */
import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { Context } from '@deepseek-ai/cordis'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import Include from '@deepseek-ai/cordis-plugin-include'
import LlmRuntime, { createAssistantMessage, createToolResultMessage, createUserMessage, ToolCallId } from '@deepseek-ai/dsh-llm'
import LocalAttachments from '@deepseek-ai/dsh-attachment-local'
import * as Pi from '../src/index.ts'
import { readEntryCredential } from '../src/enterprise-store.ts'
import { profileComposition } from '../../../settings/settings/tests/profile-composition.ts'
import { sourceModuleLoader } from '../../llm-deepseek/tests/helpers.ts'
import { assemble } from './assemble.ts'

const credentialFile = process.env.FUNNYWORK_VISION_E2E_CREDENTIAL_FILE
const chartFile = process.env.FUNNYWORK_VISION_E2E_CHART
const cleanup: Array<() => Promise<unknown>> = []
afterEach(async () => {
  while (cleanup.length) await cleanup.pop()!()
})

async function boot(protocol: string) {
  if (!credentialFile) throw new Error('An employee entry credential file is required')
  const value: unknown = JSON.parse(await readFile(credentialFile, 'utf8'))
  if (typeof value !== 'object' || value === null || !('origin' in value) || typeof value.origin !== 'string')
    throw new Error('Invalid local entry')
  const origin = new URL(value.origin)
  if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash)
    throw new Error('Vision integration requires a loopback development backend')
  const credential = await readEntryCredential(credentialFile, origin.origin)
  const response = await fetch(origin.origin + '/api/account/inference/catalog', {
    headers: { Authorization: 'Bearer ' + credential.token }, redirect: 'error', signal: AbortSignal.timeout(10000),
  })
  expect(response.ok).toBe(true)
  const catalog = await response.json() as {
    items: Array<{ id: string; provider_id: string; model: string; protocol: string; capabilities: { images: boolean } }>
  }
  const entry = catalog.items.find(row => row.model === 'deepseek-flash' && row.protocol === protocol && row.capabilities.images)
  if (!entry) throw new Error('A vision-enabled local Flash deployment is required for ' + protocol)
  const home = await mkdtemp(join(tmpdir(), 'fw-local-vision-'))
  cleanup.push(() => rm(home, { recursive: true, force: true }))
  const ctx = new Context()
  cleanup.push(() => ctx.fiber.dispose())
  ctx.baseUrl = pathToFileURL(home).href + '/'
  await ctx.plugin(Loader)
  ctx.loader.builtins.include = Include
  const modules = new Map<string, unknown>([['test-llm', LlmRuntime], ['test-pi', Pi], ['test-attachments', LocalAttachments]])
  ctx.loader.internal = sourceModuleLoader(async (specifier) => {
    if (!modules.has(specifier)) throw new Error('Unexpected fixture module')
    return modules.get(specifier)
  })
  const path = join(home, 'cordis.yml')
  await writeFile(path, JSON.stringify([
    { id: 'llm', name: 'test-llm' },
    { id: 'pi', name: 'test-pi', config: { enterprise: { origin: origin.origin, credentialFile, stateDirectory: join(home, 'calls') } } },
    { id: 'attachments', name: 'test-attachments', config: { dshHome: home } },
  ]))
  await profileComposition(ctx, home, path)
  expect((await ctx.llm.resolveModelInfo(entry.provider_id, entry.id)).inputModalities).toEqual(['text', 'image'])
  return { ctx, entry }
}

describe.skipIf(!credentialFile)('Local employee Flash vision', () => {
  it.each(['CHAT_COMPLETIONS', 'MESSAGES'])('reads an uploaded image through %s admission', async (protocol) => {
    const { ctx, entry } = await boot(protocol)
    const attachment = await ctx.attachments.saveImage({ data: await readFile(new URL('../../llm-deepseek/tests/fixtures/red.png', import.meta.url)), mediaType: 'image/png' })
    const result = await assemble(ctx, {
      provider: entry.provider_id, model: entry.id, maxTokens: 512,
      messages: [createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Name the dominant color in this image. Reply in English.' }, { type: 'image', attachment }] })],
    })
    expect(result.finish, JSON.stringify(result.finish)).toMatchObject({ kind: 'stop' })
    expect(result.message.content.filter(block => block.type === 'text').map(block => block.text).join('')).toMatch(/red/i)
  }, 90000)

  it.skipIf(!chartFile).each(['CHAT_COMPLETIONS', 'MESSAGES'])('reads a generated business chart from a %s screenshot result', async (protocol) => {
    const { ctx, entry } = await boot(protocol)
    const attachment = await ctx.attachments.saveImage({ data: await readFile(chartFile!), mediaType: 'image/png' })
    const callId = ToolCallId('sales-chart-capture')
    const result = await assemble(ctx, {
      provider: entry.provider_id, model: entry.id, maxTokens: 512,
      messages: [
        createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Read the chart screenshot. Which city has the highest March sales, and what is its March value? Answer briefly in English.' }] }),
        createAssistantMessage({ source: { provider: entry.provider_id, model: entry.id }, content: [{ type: 'tool-call', id: callId, name: 'browser_screenshot', arguments: '{}' }] }),
        createToolResultMessage({ callId, isError: false, content: [{ type: 'text', text: 'Screenshot of the sales report.' }, { type: 'image', attachment }] }),
      ],
    })
    expect(result.finish, JSON.stringify(result.finish)).toMatchObject({ kind: 'stop' })
    const text = result.message.content.filter(block => block.type === 'text').map(block => block.text).join('')
    expect(text).toMatch(/Shenzhen/i)
    expect(text).toMatch(/240/)
  }, 90000)
})
