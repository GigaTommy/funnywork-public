/** Installation defaults survive partial personal edits and are read by the actual Loader and settings editor. */
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'
import { configurationFixture } from './configuration-fixture.ts'

it('applies installation defaults below personal fields and reports their actual source', async () => {
  const f = await configurationFixture({ hmr: false })
  await f.ctx.fiber.dispose()
  writeFileSync(join(f.home, 'cordis.defaults.yml'), JSON.stringify([{ id: 'first', config: { count: 7 } }]))
  writeFileSync(f.profile.patchPath, JSON.stringify([{ id: 'first', config: { list: [{ name: 'personal' }] } }]))
  const ctx = await f.start()
  expect(ctx.settings.describe().find(row => row.ns === 'first')).toMatchObject({ value: { count: 7, list: [{ name: 'personal' }] }, base: { count: 7 }, user: { list: [{ name: 'personal' }] } })
  expect(readFileSync(f.profile.patchPath, 'utf8')).not.toContain('count')
  await ctx.fiber.dispose()
  writeFileSync(f.profile.patchPath, JSON.stringify([{ id: 'first', config: { count: 9 } }]))
  const overridden = await f.start()
  expect(overridden.settings.describe().find(row => row.ns === 'first')).toMatchObject({ value: { count: 9 }, base: { count: 7 }, user: { count: 9 } })
})

it('rejects installation defaults that attempt lifecycle or plugin insertion changes', async () => {
  const f = await configurationFixture({ hmr: false })
  await f.ctx.fiber.dispose()
  for (const row of [{ id: 'first', disabled: true, config: {} }, { insert: [{ id: 'extra', name: 'cordis:probe' }] }, { config: {} }]) {
    writeFileSync(join(f.home, 'cordis.defaults.yml'), JSON.stringify([row]))
    await expect(f.start()).rejects.toThrow('Installation defaults require')
  }
})

it('does not turn an inherited default into a personal choice when another field changes', async () => {
  const f = await configurationFixture({ hmr: false })
  await f.ctx.fiber.dispose()
  writeFileSync(join(f.home, 'cordis.defaults.yml'), JSON.stringify([{ id: 'first', config: { count: 7 } }]))
  const ctx = await f.start()
  await ctx.settings.mutate('first', [{ op: 'set', path: ['list'], value: [{ name: 'personal' }] }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).not.toHaveProperty('count')
  await ctx.settings.mutate('first', [{ op: 'set', path: ['count'], value: 7 }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).toHaveProperty('count', 7)
  await ctx.settings.mutate('first', [{ op: 'unset', path: ['count'] }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).not.toHaveProperty('count')
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.value).toHaveProperty('count', 7)
  await ctx.settings.mutate('first', [{ op: 'unset', path: ['count'] }, { op: 'set', path: ['count'], value: 7 }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).toHaveProperty('count', 7)
  await ctx.settings.mutate('first', [{ op: 'set', path: [], value: { count: 7 } }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).toHaveProperty('count', 7)
  await ctx.settings.mutate('first', [{ op: 'unset', path: [] }])
  expect(ctx.settings.describe().find(row => row.ns === 'first')?.user).not.toHaveProperty('count')
})
