import { createSnapshotStore } from '@deepseek-ai/dsh-client-store'
import { describe, expect, it, vi } from 'vitest'
import type { SettingsNamespaceView } from '@deepseek-ai/dsh-api-remotes/client'
import { SettingsDescribeMirror } from '@deepseek-ai/dsh-client-ui-settings/src/client/settings-mirror.ts'
import { PermissionCatalogDirectory, PermissionHostConnectingError } from '../src/client/catalog.ts'
import type { PermissionCatalog } from '@deepseek-ai/dsh-permission-presets/client'
import { RemoteError } from '@deepseek-ai/dsh-client-test-runtime'
import {
  PermissionPresetSettingsController, permissionDefaultOf,
} from '../src/client/settings-store.ts'

const SCHEMA = {
  uid: 6,
  refs: {
    1: { type: 'const', value: 'read-only' },
    2: { type: 'const', meta: { description: 'Workspace' }, value: 'workspace-write' },
    3: { type: 'union', list: [1, 2] },
    6: { type: 'object', dict: { defaultPreset: 3 } },
  },
}

const catalog = { options: [], defaultPreset: 'read-only', defaultOptions: [{ value: 'read-only', name: 'read-only' }, { value: 'workspace-write', name: 'Workspace' }] }
const directory = { store: createSnapshotStore({ value: catalog, error: null as string | null }), load: () => Promise.resolve(catalog) }

function resolveDefault(view: SettingsNamespaceView) {
  return permissionDefaultOf(view, catalog)
}

function view(defaultPreset: string, revision = 0, schema: SettingsNamespaceView['schema'] = SCHEMA): SettingsNamespaceView {
  return {
    ns: 'permission',
    schema,
    value: { defaultPreset },
    base: { defaultPreset: 'read-only' },
    autoGenerate: true, applies: 'live',
    secrets: [],
    revision,
  }
}

/** The settings namespace answers over the Remote carrier, which has no envelope. */
function ok<T>(value: T) {
  return { ok: true as const, value }
}

/** The permission controller over a real mirror and one scripted context. */
function permissionController(api: object) {
  const ctx = { remote: { settings: api } } as never
  const mirror = new SettingsDescribeMirror(ctx)
  return { mirror, controller: new PermissionPresetSettingsController(mirror, ctx, directory) }
}

describe('permission settings store', () => {
  it('waits for the Host connection and disables stale choices until reconnection settles', async () => {
    const ctx = { remote: { settings: {
      describe: async () => ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] }),
      mutate: vi.fn(),
    } } }
    const mirror = new SettingsDescribeMirror(ctx as never)
    const source = { store: createSnapshotStore<{ value: PermissionCatalog | null; error: string | null }>({ value: null, error: null }),
      load: async () => { throw new PermissionHostConnectingError() } }
    const controller = new PermissionPresetSettingsController(mirror, ctx as never, source)
    await controller.load()
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'loading', error: null, writable: false, options: [] })
    source.store.set({ value: catalog, error: null })
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'ready', currentValue: 'read-only', writable: true })
    source.store.set({ value: null, error: null })
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'loading', error: null, writable: false, options: [] })
    await controller.select('workspace-write')
    expect(ctx.remote.settings.mutate).not.toHaveBeenCalled()
    source.store.set({ value: catalog, error: null })
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'ready', writable: true })
    controller.dispose()
  })

  it('derives configured options from the permission catalog and preserves the inferred default', () => {
    expect(resolveDefault(view('read-only'))).toEqual({ currentValue: 'read-only', options: [
      { id: 'read-only', label: 'Read Only' }, { id: 'workspace-write', label: 'Workspace' },
    ] })
    expect(resolveDefault({ ...view('read-only'), value: {} }).currentValue).toBe('read-only')
    expect(() => resolveDefault(view('missing'))).toThrow('does not advertise')
  })

  it('loads and writes defaultPreset with optimistic concurrency', async () => {
    const describe = vi.fn(() => Promise.resolve(ok({
      writable: true,
      hasDocument: false,
      namespaces: [view('read-only', 4)],
    })))
    const mutate = vi.fn(() => Promise.resolve(ok(view('workspace-write', 5))))
    const { controller } = permissionController({ describe, mutate })
    await controller.load()
    expect(controller.store.getSnapshot()).toMatchObject({
      status: 'ready',
      writable: true,
      currentValue: 'read-only',
      revision: 4,
    })
    await controller.select('workspace-write')
    expect(mutate).toHaveBeenCalledWith(
      'permission',
      [{ op: 'set', path: ['defaultPreset'], value: 'workspace-write' }],
      4,
    )
    expect(controller.store.getSnapshot()).toMatchObject({
      status: 'ready',
      currentValue: 'workspace-write',
      revision: 5,
    })
    // The write answer folded into the mirror; no re-read followed.
    expect(describe).toHaveBeenCalledTimes(1)
  })

  it('hides the row when the namespace is absent and contains write failures', async () => {
    const describe = vi.fn(() => Promise.resolve(ok({ writable: true, hasDocument: false, namespaces: [] })))
    const { controller } = permissionController({ describe, mutate: vi.fn() })
    await controller.load()
    expect(controller.store.getSnapshot().status).toBe('unavailable')

    const failing = permissionController({
      describe: () => Promise.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] })),
      mutate: () => Promise.resolve({
        ok: false as const,
        error: new RemoteError('settings/conflict', 'stale', { ns: 'permission', expected: 1, actual: 2 }),
      }),
    }).controller
    await failing.load()
    await failing.select('workspace-write')
    expect(failing.store.getSnapshot()).toMatchObject({ status: 'error', error: 'stale' })
  })

  it('contains read failures and no-ops without a writable view', async () => {
    const mutate = vi.fn()
    const readOnly = permissionController({
      describe: () => Promise.resolve(ok({
        writable: false, hasDocument: false, namespaces: [view('read-only', 2)],
      })),
      mutate,
    }).controller
    await readOnly.load()
    expect(readOnly.store.getSnapshot()).toMatchObject({
      currentValue: 'read-only',
      writable: false,
      revision: 2,
    })
    await readOnly.select('workspace-write')
    expect(mutate).not.toHaveBeenCalled()

    const rejected = permissionController({
      describe: () => Promise.resolve({
        ok: false as const,
        error: new RemoteError('gateway/internal', 'offline', {}),
      }),
      mutate,
    }).controller
    await rejected.select('workspace-write')
    await rejected.load()
    expect(rejected.store.getSnapshot()).toMatchObject({ status: 'error', error: 'offline' })
    expect(mutate).not.toHaveBeenCalled()

    const thrown = permissionController({
      describe: async () => { throw 'disconnected' },
      mutate,
    }).controller
    await thrown.load()
    expect(thrown.store.getSnapshot()).toMatchObject({ status: 'error', error: 'disconnected' })

    const ctx = {
      remote: {
        settings: {
          describe: () => Promise.resolve(ok({
            writable: true, hasDocument: false, namespaces: [view('read-only')],
          })),
          mutate,
        },
      },
    } as never
    const mirror = new SettingsDescribeMirror(ctx)
    const malformed = new PermissionPresetSettingsController(mirror, ctx, {
      store: directory.store, load: () => Promise.reject(new Error('catalog disconnected')),
    })
    await malformed.load()
    expect(malformed.store.getSnapshot()).toMatchObject({
      status: 'error', error: 'catalog disconnected',
    })
  })

  it('follows a mirror refresh without an own read once loaded', async () => {
    const describe = vi.fn()
      .mockResolvedValueOnce(ok({ writable: true, hasDocument: false, namespaces: [view('read-only', 1)] }))
      .mockResolvedValueOnce(ok({ writable: true, hasDocument: false, namespaces: [view('workspace-write', 2)] }))
    const { mirror, controller } = permissionController({ describe, mutate: vi.fn() })
    await controller.load()
    expect(controller.store.getSnapshot()).toMatchObject({ currentValue: 'read-only' })

    await mirror.load()

    expect(controller.store.getSnapshot()).toMatchObject({ currentValue: 'workspace-write', revision: 2 })
  })

  it('does not load the catalog when disposed while waiting for the settings descriptor', async () => {
    const answer = Promise.withResolvers<ReturnType<typeof ok<{
      writable: boolean
      hasDocument: boolean
      namespaces: SettingsNamespaceView[]
    }>>>()
    const describe = vi.fn(() => answer.promise)
    const load = vi.spyOn(directory, 'load')
    const { controller } = permissionController({ describe, mutate: vi.fn() })
    try {
      const loading = controller.load()
      await vi.waitFor(() => { expect(describe).toHaveBeenCalledOnce() })
      controller.dispose()
      const disposed = controller.store.getSnapshot()
      answer.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] }))
      await loading
      expect(load).not.toHaveBeenCalled()
      expect(controller.store.getSnapshot()).toBe(disposed)
    } finally {
      controller.dispose()
      load.mockRestore()
    }
  })

  it('disposal stops deriving and suppresses in-flight writes', async () => {
    const neverRead = vi.fn()
    const { controller: neverLoaded } = permissionController({ describe: neverRead, mutate: vi.fn() })
    neverLoaded.dispose()
    await neverLoaded.load()
    expect(neverLoaded.store.getSnapshot().status).toBe('idle')
    expect(neverRead).not.toHaveBeenCalled()

    const read = Promise.withResolvers<ReturnType<typeof ok<{
      writable: boolean
      namespaces: SettingsNamespaceView[]
    }>>>()
    const { mirror, controller: idle } = permissionController({ describe: () => read.promise, mutate: vi.fn() })
    const loading = idle.load()
    const atDisposal = idle.store.getSnapshot()
    idle.dispose()
    read.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] }))
    await Promise.all([loading, mirror.load()])
    expect(idle.store.getSnapshot()).toBe(atDisposal)

    const mutation = Promise.withResolvers<ReturnType<typeof ok<SettingsNamespaceView>>>()
    const { controller: active } = permissionController({
      describe: () => Promise.resolve(ok({
        writable: true,
        hasDocument: false,
        namespaces: [view('read-only')],
      })),
      mutate: () => mutation.promise,
    })
    await active.load()
    const saving = active.select('workspace-write')
    active.dispose()
    mutation.resolve(ok(view('workspace-write', 1)))
    await saving
    expect(active.store.getSnapshot().status).toBe('saving')

    const refusedMutation = Promise.withResolvers<
      ReturnType<typeof ok<SettingsNamespaceView>> | { ok: false; error: RemoteError }
    >()
    const { controller: disposedWrite } = permissionController({
      describe: () => Promise.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] })),
      mutate: () => refusedMutation.promise,
    })
    await disposedWrite.load()
    const writing = disposedWrite.select('workspace-write')
    disposedWrite.dispose()
    refusedMutation.resolve({
      ok: false,
      error: new RemoteError('settings/conflict', 'late write', { ns: 'permission', expected: 1, actual: 2 }),
    })
    await writing
    expect(disposedWrite.store.getSnapshot().status).toBe('saving')
  })
})

it('waits for the catalog, follows catalog changes, and contains invalid defaults', async () => {
  const ctx = { remote: { settings: { describe: async () => ok({ writable: true, hasDocument: true, namespaces: [view('read-only')] }) } } } as never
  const mirror = new SettingsDescribeMirror(ctx)
  const store = createSnapshotStore<{ value: typeof catalog | null; error: string | null }>({ value: null, error: null })
  const controller = new PermissionPresetSettingsController(mirror, ctx, { store, load: async () => catalog })
  await controller.load()
  expect(controller.store.getSnapshot().status).toBe('loading')
  store.set({ value: catalog, error: null })
  expect(controller.store.getSnapshot().status).toBe('ready')
  store.set({ value: { ...catalog, defaultOptions: [] }, error: null })
  expect(controller.store.getSnapshot().status).toBe('error')
  controller.dispose()
  const failed = new PermissionPresetSettingsController(mirror, ctx, { store, load: async () => { throw 'catalog unavailable' } })
  await failed.load()
  expect(failed.store.getSnapshot()).toMatchObject({ status: 'error', error: 'catalog unavailable' })
  failed.dispose()
})

it('publishes automatic refresh failures and coalesces retries until a fresh default is available', async () => {
  const refresh = Promise.withResolvers<{ ok: false; error: { code: string; message: string } }>()
  const retryRead = Promise.withResolvers<{ ok: true; value: typeof catalog }>()
  const read = vi.fn().mockResolvedValueOnce(ok(catalog)).mockImplementationOnce(() => refresh.promise)
    .mockImplementationOnce(() => retryRead.promise)
  const defaultPreset = 'read-only'
  const describe = vi.fn(async () => ok({ writable: true, hasDocument: false, namespaces: [view(defaultPreset)] }))
  const ctx = { get: () => ({ generation: { getSnapshot: () => ({ id: 1 }), subscribe: () => () => {} } }),
    remote: { $on: () => () => {}, permissionPresets: { catalog: read }, settings: { describe } } }
  const directory = new PermissionCatalogDirectory(ctx as never)
  const mirror = new SettingsDescribeMirror(ctx as never)
  const controller = new PermissionPresetSettingsController(mirror, ctx as never, directory)
  try {
    await controller.load()
    expect(controller.store.getSnapshot().status).toBe('ready')
    directory.refresh()
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'loading', writable: false })
    refresh.resolve({ ok: false, error: { code: 'catalog/failed', message: 'private transport detail' } })
    await vi.waitFor(() => { expect(controller.store.getSnapshot()).toMatchObject({ status: 'error', writable: false, options: [] }) })
    expect(read).toHaveBeenCalledTimes(2)
    mirror.acceptView(view('workspace-write', 1))
    const first = controller.load()
    const second = controller.load()
    expect(second).toBe(first)
    await vi.waitFor(() => { expect(read).toHaveBeenCalledTimes(3) })
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'loading', error: null, writable: false })
    retryRead.resolve(ok(catalog))
    await Promise.all([first, second])
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'ready', error: null, writable: true, currentValue: 'workspace-write' })
    expect(describe).toHaveBeenCalledTimes(1)
    expect(read).toHaveBeenCalledTimes(3)
  } finally { controller.dispose(); directory.dispose() }
})

it('replaces a metadata read failure with waiting while the mirror owner retries', async () => {
  type SettingsView = { writable: boolean; hasDocument: boolean; namespaces: SettingsNamespaceView[] }
  const retry = Promise.withResolvers<ReturnType<typeof ok<SettingsView>>>()
  const describe = vi.fn().mockRejectedValueOnce(new Error('settings unavailable')).mockImplementationOnce(() => retry.promise)
  const ctx = { remote: { settings: { describe } } } as never
  const mirror = new SettingsDescribeMirror(ctx)
  const controller = new PermissionPresetSettingsController(mirror, ctx, directory)
  try {
    await controller.load()
    expect(controller.store.getSnapshot().status).toBe('error')
    const ownerRead = mirror.load()
    await vi.waitFor(() => { expect(describe).toHaveBeenCalledTimes(2) })
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'loading', error: null, writable: false, options: [] })
    retry.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] }))
    await ownerRead
    expect(controller.store.getSnapshot()).toMatchObject({ status: 'ready', error: null, currentValue: 'read-only' })
  } finally {
    retry.resolve(ok({ writable: true, hasDocument: false, namespaces: [view('read-only')] }))
    controller.dispose()
  }
})
