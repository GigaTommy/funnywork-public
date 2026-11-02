/** Host-owned enterprise catalog. All model dispatch passes through server admission. */
import type { Context } from '@deepseek-ai/cordis'
import { funnyWorkTransport } from '@deepseek-ai/dsh-http-proxy'
import { brandString } from '@deepseek-ai/dsh-brand'
import type { Branded } from '@deepseek-ai/dsh-brand'
import { randomUUID } from 'node:crypto'
import { isAbsolute } from 'node:path'
import {
  LlmAdapter,
  LlmError,
  ModelAdmissionTicketId,
  ReasoningEffortId,
  resolveRetryPolicy,
  resolveImageAttachmentAccess,
} from '@deepseek-ai/dsh-llm'
import type { AdapterRegistrationHandle, GenerateOptions, PreparedAdapterCall } from '@deepseek-ai/dsh-llm'
import { DeepSeekAdapter, resolveAdapterOptions, catalogModelInfo } from '@deepseek-ai/dsh-llm-deepseek'
import type { DeepSeekAdapterOptions, DeepSeekCatalogModel } from '@deepseek-ai/dsh-llm-deepseek'
import { PiAiAdapter } from './adapter.ts'
import { resolveProfiles } from './config.ts'
import { authContextFrom, credentialStoreFrom } from './auth.ts'
import { admissionTransport } from './admission.ts'
import type { AdmissionTransport } from './admission.ts'
import { EnterpriseAdmissionStore, readEntryCredential } from './enterprise-store.ts'
import { installEnterprisePresets } from './enterprise-presets.ts'
import type { EnterpriseOptions } from './enterprise-config.ts'

type DeploymentId = Branded<'EnterpriseDeploymentId'>
interface Entry {
  id: DeploymentId
  name: string
  provider: string
  providerName: string
  model: string
  protocol: 'CHAT_COMPLETIONS' | 'MESSAGES'
  context: number
  output: number
  tools: boolean
  reasoning: boolean
  images: boolean
  streamUsage: boolean
}

function catalog(value: unknown): Entry[] {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('items' in value) ||
    !Array.isArray(value.items) ||
    value.items.length > 512
  )
    throw new LlmError('Invalid enterprise catalog', 'ADMISSION_PROTOCOL')
  const ids = new Set<string>()
  return value.items.map((row: unknown) => {
    if (
      typeof row !== 'object' ||
      row === null ||
      !('id' in row) ||
      typeof row.id !== 'string' ||
      !/^[A-Za-z0-9_-]{1,64}$/.test(row.id) ||
      ids.has(row.id) ||
      !('name' in row) ||
      typeof row.name !== 'string' ||
      row.name.length === 0 ||
      row.name.length > 120 ||
      !('model' in row) ||
      typeof row.model !== 'string' ||
      row.model.length === 0 ||
      row.model.length > 120 ||
      !('protocol' in row) ||
      (row.protocol !== 'CHAT_COMPLETIONS' && row.protocol !== 'MESSAGES') ||
      !('context_tokens' in row) ||
      typeof row.context_tokens !== 'number' ||
      !Number.isSafeInteger(row.context_tokens) ||
      row.context_tokens < 128 ||
      row.context_tokens > 2000000 ||
      !('max_output_tokens' in row) ||
      typeof row.max_output_tokens !== 'number' ||
      !Number.isSafeInteger(row.max_output_tokens) ||
      row.max_output_tokens < 1 ||
      row.max_output_tokens > row.context_tokens ||
      !('capabilities' in row) ||
      typeof row.capabilities !== 'object' ||
      row.capabilities === null ||
      Array.isArray(row.capabilities)
    )
      throw new LlmError('Invalid enterprise deployment', 'ADMISSION_PROTOCOL')
    ids.add(row.id)
    const capabilities: Record<string, unknown> = row.capabilities as Record<string, unknown>
    for (const flag of ['tools', 'reasoning', 'images', 'streamUsage'])
      if (capabilities[flag] !== undefined && typeof capabilities[flag] !== 'boolean')
        throw new LlmError('Invalid enterprise capability', 'ADMISSION_PROTOCOL')
    const provider = 'provider_id' in row ? row.provider_id : `enterprise-${row.id}`
    const providerName = 'provider_name' in row ? row.provider_name : row.name
    if (typeof provider !== 'string' || !/^enterprise-[a-zA-Z0-9_-]{1,64}$/.test(provider) ||
        typeof providerName !== 'string' || providerName.length === 0 || providerName.length > 120)
      throw new LlmError('Invalid enterprise provider', 'ADMISSION_PROTOCOL')
    return {
      provider,
      providerName,
      id: brandString<DeploymentId>(row.id),
      name: row.name,
      model: row.model,
      protocol: row.protocol,
      context: row.context_tokens,
      output: row.max_output_tokens,
      tools: capabilities.tools === true,
      reasoning: capabilities.reasoning === true,
      images: capabilities.images === true,
      streamUsage: capabilities.streamUsage === true,
    }
  })
}

interface Route {
  entry: Entry
  adapter: LlmAdapter
}

function deepSeekThinking(model: string): boolean {
  return model === 'deepseek-flash' || model === 'deepseek-v4-pro'
}

class EnterpriseAdapter extends LlmAdapter {
  constructor(private readonly routes: () => ReadonlyMap<string, ReadonlyMap<string, Route>>) {
    super()
  }
  private group(provider: string): ReadonlyMap<string, Route> {
    const group = this.routes().get(provider)
    if (group === undefined) throw new LlmError('Enterprise provider is unavailable', 'MODEL_UNAVAILABLE')
    return group
  }
  private route(provider: string, model: string): Route {
    const route = this.group(provider).get(model)
    if (route === undefined) throw new LlmError('Enterprise deployment is unavailable', 'MODEL_UNAVAILABLE')
    return route
  }
  private options(options: GenerateOptions, entry: Entry): GenerateOptions {
    return {
      ...options,
      model: entry.model,
      ...(deepSeekThinking(entry.model) && options.purpose !== undefined
        ? { reasoningEffort: ReasoningEffortId('off') } : {}),
      messages: options.messages.map(message => message.role === 'assistant' &&
        message.source.provider === options.provider && message.source.model === options.model
        ? { ...message, source: { ...message.source, model: entry.model } } : message),
    }
  }
  override providerInfo(provider: string) {
    const first = this.group(provider).values().next().value
    if (first === undefined) throw new LlmError('Enterprise provider is empty', 'MODEL_UNAVAILABLE')
    return { id: provider, name: first.entry.providerName }
  }
  override providerRetryPolicy(_provider: string) {
    return resolveRetryPolicy({ mode: 'normal', maxRetries: 0 }, 'Enterprise admission retry policy')
  }
  override async listModels(provider: string) {
    const models = await Promise.all([...this.group(provider)].map(async ([id, route]) => ({
      ...await route.adapter.resolveModel(provider, route.entry.model), id,
    })))
    return models
  }
  override async resolveModel(provider: string, model: string, signal?: AbortSignal) {
    const route = this.route(provider, model)
    return { ...await route.adapter.resolveModel(provider, route.entry.model, signal), id: model }
  }
  override async prepareCall(provider: string, model: string, signal?: AbortSignal): Promise<PreparedAdapterCall> {
    const route = this.route(provider, model)
    const prepared = await route.adapter.prepareCall(provider, route.entry.model, signal)
    return { model: { ...prepared.model, id: model }, stream: options => prepared.stream(this.options(options, route.entry)) }
  }
  override stream(options: GenerateOptions) {
    const route = this.route(options.provider, options.model)
    return route.adapter.stream(this.options(options, route.entry))
  }
}

/**
 * Register and refresh only authenticated enterprise models.
 * @param ctx - the plugin owner and LLM registry.
 * @param config - validated Electron-owned paths and transport policy.
 * @returns once the initial authorized catalog is registered.
 */
export async function applyEnterprise(ctx: Context, config: EnterpriseOptions): Promise<void> {
  if (!isAbsolute(config.credentialFile) || !isAbsolute(config.stateDirectory) || config.maxPollMs < config.pollMs)
    throw new Error('Invalid enterprise deployment paths or polling policy')
  const origin = new URL(config.origin)
  if (
    origin.origin !== config.origin ||
    (origin.protocol !== 'https:' &&
      !(origin.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(origin.hostname)))
  )
    throw new Error('Invalid enterprise origin')
  const catalogHttp = funnyWorkTransport(config.origin, (url, init) => fetch(url, init))
  const lifetime = new AbortController()
  const cancelled = () => lifetime.signal.aborted
  const transports: AdmissionTransport[] = []
  let routes = new Map<string, ReadonlyMap<string, Route>>(),
    fingerprint = '',
    registration: AdapterRegistrationHandle | undefined,
    inflight: Promise<void> | undefined,
    disposed = false
  ctx.effect(
    () => async () => {
      disposed = true
      lifetime.abort(new Error('Enterprise model plugin stopped'))
      await Promise.all(transports.map(transport => transport.dispose()))
      await inflight?.catch(() => {
        /* Refresh observes the lifetime abort. */
      })
    },
    'enterprise model lifetime',
  )
  const initial = await readEntryCredential(config.credentialFile, config.origin),
    store = new EnterpriseAdmissionStore(config.stateDirectory)
  if (cancelled()) return
  const adapter = new EnterpriseAdapter(() => routes),
    auth = { credentials: credentialStoreFrom(ctx), authContext: authContextFrom(ctx) }
  const anonymous = brandString<ReturnType<DeepSeekAdapterOptions['resolveUserId']>>(randomUUID())
  const credentials = async () => {
    const current = await readEntryCredential(config.credentialFile, config.origin)
    if (current.device !== initial.device)
      throw new LlmError('Enterprise device identity changed', 'ENTRY_IDENTITY_CHANGED')
    return current.token
  }
  installEnterprisePresets(ctx, config, credentials)
  const makeAdapter = (entry: Entry, provider: string): LlmAdapter => {
    const deepSeek = entry.model === 'deepseek-flash' || entry.model === 'deepseek-v4-pro'
    const admitted = admissionTransport({
      origin: config.origin,
      deploymentId: entry.id,
      model: entry.model,
      deviceId: initial.device,
      pollMs: config.pollMs,
      maxPollMs: config.maxPollMs,
      jitterRatio: config.jitterRatio,
      requestTimeoutMs: config.requestTimeoutMs,
      credential: credentials,
      acquire: key => store.acquire(key),
      save: (key, record) => store.save(key, record),
      remove: (key, record) => store.remove(key, record),
      waiting: (options, ticketId, waiting) => {
        ctx.llm.setAdmissionWaiting(options.sessionId, ModelAdmissionTicketId(ticketId), waiting)
      },
    })
    transports.push(admitted)
    const prepareHttp: import('@deepseek-ai/dsh-llm').PrepareLlmHttp = async (options, payload, signal) => {
      const request: unknown = JSON.parse(payload)
      if (
        !entry.tools &&
        typeof request === 'object' &&
        request !== null &&
        'tools' in request &&
        Array.isArray(request.tools) &&
        request.tools.length > 0
      )
        throw new LlmError('This deployment does not support tool calls', 'UNSUPPORTED_TOOLS')
      return await admitted(options, payload, AbortSignal.any([signal, lifetime.signal]))
    }
    if (entry.protocol === 'MESSAGES') {
      const model: DeepSeekCatalogModel = {
        id: entry.model, name: entry.name, contextWindow: entry.context, maxTokens: entry.output,
        inputModalities: entry.images ? ['text', 'image'] : ['text'],
      }
      const connection = resolveAdapterOptions({
        baseURL: config.origin + '/protocol-placeholder',
        models: [model],
        maxTokens: entry.output,
        thinking: entry.reasoning ? 'enabled' : 'disabled',
        streamIdleTimeoutMs: config.streamIdleTimeoutMs,
        maxInlineRequestImageBytes: config.maxRequestImageBytes,
        inlineImageOffloadByteQuantum: 1,
      })
      return new DeepSeekAdapter({
        options: () => connection,
        providerName: entry.providerName,
        discoverModels: route => Promise.resolve([catalogModelInfo(route, model)]),
        resolveAuth: () => Promise.resolve({ headers: {} }),
        resolveUserId: () => anonymous,
        resolveAttachments: () => ctx.get('attachments'),
        resolveImageAccess: (attachments, ref) =>
          resolveImageAttachmentAccess(attachments, path => ctx.get('fs')?.processPathFromHostPath(path), ref),
        prepareExtensions: () => Promise.resolve({ fields: {}, accept: () => Promise.resolve() }),
        prepareHttp,
      })
    }
    const profiles = resolveProfiles({
      [provider]: {
        displayName: entry.providerName,
        api: 'openai-completions',
        baseURL: config.origin + '/protocol-placeholder',
        streamIdleTimeoutMs: config.streamIdleTimeoutMs,
        maxRequestImageBytes: config.maxRequestImageBytes,
        ...deepSeek && entry.reasoning ? { reasoning: 'high' as const } : {},
        compat: {
          supportsStore: false,
          supportsUsageInStreaming: entry.streamUsage,
          ...deepSeek ? { thinkingFormat: 'deepseek' as const, supportsReasoningEffort: true, maxTokensField: 'max_tokens' as const, supportsDeveloperRole: false } : {},
        },
        models: [
          {
            id: entry.model,
            name: entry.name,
            contextWindow: entry.context,
            maxTokens: entry.output,
            input: entry.images ? ['text', 'image'] : ['text'],
            reasoningEfforts: deepSeek && entry.reasoning
              ? { off: null, low: 'low', high: 'high', max: 'max' }
              : false,
          },
        ],
      },
    })
    return new PiAiAdapter({
      profiles: () => profiles,
      resolveApiKey: () => Promise.resolve('enterprise-entry'),
      auth,
      prepareHttp,
      resolveAttachments: () => ctx.get('attachments'),
      resolveImageAccess: (attachments, ref) =>
        resolveImageAttachmentAccess(attachments, path => ctx.get('fs')?.processPathFromHostPath(path), ref),
    })
  }
  const refresh = async () => {
    const token = await credentials()
    const response = await catalogHttp.fetch(config.origin + '/api/account/inference/catalog', {
      headers: { Authorization: `Bearer ${token}` },
      redirect: 'error',
      signal: AbortSignal.any([lifetime.signal, AbortSignal.timeout(config.requestTimeoutMs)]),
    })
    if (!response.ok) throw new LlmError(`Enterprise directory returned ${response.status}`, 'CATALOG_UNAVAILABLE')
    const text = await response.text()
    if (Buffer.byteLength(text) > 262144) throw new LlmError('Enterprise catalog is too large', 'ADMISSION_PROTOCOL')
    const raw: unknown = JSON.parse(text),
      entries = catalog(raw).sort((a, b) => a.id.localeCompare(b.id)),
      nextFingerprint = JSON.stringify(entries)
    if (disposed || nextFingerprint === fingerprint) return
    const next = new Map<string, Map<string, Route>>()
    for (const entry of entries) {
      const provider = entry.provider
      const group = next.get(provider) ?? new Map<string, Route>()
      if ([...group.values()].some(route => route.entry.providerName !== entry.providerName))
        throw new LlmError('Conflicting enterprise provider names', 'ADMISSION_PROTOCOL')
      // Old servers retain their deployment route and upstream model ids.
      const model = provider === `enterprise-${entry.id}` ? entry.model : entry.id
      const previous = routes.get(provider)?.get(model)
      const current = previous !== undefined && JSON.stringify(previous.entry) === JSON.stringify(entry)
        ? previous : { entry, adapter: makeAdapter(entry, provider) }
      group.set(model, current)
      next.set(provider, group)
    }
    const previous = routes
    routes = next
    try {
      if (registration === undefined) {
        if (next.size > 0) registration = ctx.llm.registerAdapter([...next.keys()], adapter)
      } else registration.replace([...next.keys()])
    } catch (error) {
      routes = previous
      throw error
    }
    fingerprint = nextFingerprint
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await refresh()
      break
    } catch (_catalogUnavailable) {
      if (cancelled()) return
      if (attempt === 2) ctx.logger.warn('Initial enterprise model catalog is unavailable; refresh will retry')
    }
  }
  // Read after the await: disposal may have occurred while the catalog was loading.
  if (cancelled()) return
  ctx.effect(() => {
    const timer = setInterval(() => {
      if (disposed || inflight !== undefined) return
      inflight = refresh()
        .catch(() => {
          if (!disposed) ctx.logger.warn('Enterprise model catalog refresh is unavailable')
        })
        .finally(() => {
          inflight = undefined
        })
    }, config.refreshMs)
    return () => {
      clearInterval(timer)
    }
  }, 'enterprise catalog refresh')
}
