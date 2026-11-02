/** Built-profile browser coverage for sticky model groups; counts only their synchronous rect reads, not FPS or latency. */
import { fileURLToPath } from 'node:url'
import type { Browser, Locator, Page } from 'playwright'
import { chromium, webkit } from 'playwright'
import { afterAll, beforeAll, describe, expect, it, onTestFailed } from 'vitest'
import { launchWebScaffold, watchConsole, webSnapshotMode, type WebScaffold } from './scaffold.ts'
import { ZH_BROWSER_LOCALE, connectFreshWorkspaceZh, saveFailureShot } from './support.ts'

declare global {
  interface Window {
    /** Test-private count for MenuGroup sections and their direct scroll viewport. */
    __modelMenuRectReads: number
  }
}

const OVERLAY = fileURLToPath(new URL('./default-model.overlay.yml', import.meta.url))
const LONG_MODEL_NAME = 'Origin Large · 企业正式环境长上下文工具调用与思考模式专用模型 · ' + 'LongModelName'.repeat(12)
const PROVIDERS = [
  { id: 'origin-gateway', name: 'Origin Gateway', prefix: 'Origin' },
  { id: 'amber-gateway', name: 'Amber Gateway', prefix: 'Amber' },
  { id: 'cedar-gateway', name: 'Cedar Gateway', prefix: 'Cedar' },
  { id: 'quartz-gateway', name: 'Quartz Gateway', prefix: 'Quartz' },
].map((provider, providerIndex) => ({
  ...provider,
  models: Array.from({ length: 7 }, (_, index) => providerIndex === 0 && index === 0
    ? { id: 'origin-large', name: LONG_MODEL_NAME }
    : { id: `${provider.prefix.toLowerCase()}-model-${index + 1}`, name: `${provider.prefix} Model ${index + 1}` }),
}))
const EXPECTED_GROUPS = [
  { label: 'DeepSeek', rows: ['DeepSeek-V4-Flash', 'DeepSeek-V4-Flash-Vision-Exp'] },
  ...PROVIDERS.map(provider => ({ label: provider.name, rows: provider.models.map(model => model.name) })),
]

// Native IO/RO callbacks need rendering opportunities, not elapsed-time sleeps.
async function frames(page: Page): Promise<void> {
  await page.evaluate(() => new Promise<void>((resolve) => {
    requestAnimationFrame(() => { requestAnimationFrame(() => { resolve() }) })
  }))
}

function readGroups(viewport: Locator) {
  return viewport.locator('[data-menu-group]').evaluateAll(groups => groups.map(group => ({
    label: group.querySelector('[data-menu-group-heading]')!.textContent,
    rows: [...group.querySelectorAll('[role="option"], [role="menuitemradio"]')].map(row => row.textContent),
  })))
}

function readPinned(viewport: Locator) {
  return viewport.locator('[data-menu-group-heading]').evaluateAll(headings =>
    headings.map(heading => heading.hasAttribute('data-stuck')))
}

async function assertNoRectReads(page: Page): Promise<void> {
  await frames(page)
  expect(await page.evaluate(() => window.__modelMenuRectReads)).toBe(0)
}

async function assertPinned(page: Page, viewport: Locator, index: number, count = 5): Promise<void> {
  const expected = Array.from({ length: count }, (_, i) => i === index)
  await expect.poll(() => readPinned(viewport)).toEqual(expected)
  await assertNoRectReads(page)
  expect(await readPinned(viewport)).toEqual(expected)
}

async function scrollToGroup(viewport: Locator, index: number, inside = 1): Promise<void> {
  // Sections share an offset parent. Their normal-flow offsets are test positioning, not rect instrumentation.
  await viewport.evaluate((node, target) => new Promise<void>((resolve) => {
    requestAnimationFrame(() => {
      const groups = node.querySelectorAll<HTMLElement>(':scope > [data-menu-group]')
      node.scrollTop = groups[target.index]!.offsetTop - groups[0]!.offsetTop + target.inside
      resolve()
    })
  }), { index, inside })
}

async function headingMaterial(viewport: Locator) {
  return viewport.locator('[data-menu-group-heading]').first().evaluate((heading) => {
    const body = heading.ownerDocument.body
    const theme = body.getAttribute('data-ds-dark-theme')
    try {
      return [false, true].map((dark) => {
        body.toggleAttribute('data-ds-dark-theme', dark)
        const style = getComputedStyle(heading)
        return {
          fill: style.backgroundColor, radius: style.borderRadius, position: style.position,
          font: style.fontSize, weight: style.fontWeight, padding: style.padding,
        }
      })
    } finally {
      if (theme === null) body.removeAttribute('data-ds-dark-theme')
      else body.setAttribute('data-ds-dark-theme', theme)
    }
  })
}

describe.skipIf(webSnapshotMode() === 'record').each([
  { name: 'Chromium', engine: chromium },
  { name: 'WebKit', engine: webkit },
])('web e2e: model menu observers ($name)', ({ engine }) => {
  let scaffold: WebScaffold
  let browser: Browser
  let page: Page
  let tripwire: ReturnType<typeof watchConsole>
  let crashed = false

  beforeAll(async () => {
    scaffold = await launchWebScaffold({ extraOverlayPath: OVERLAY })
    // Catalog-only settings traffic: no prompt, selection, replay fixture, or model request.
    await scaffold.ctx.settings.update('llm-pi-ai', {
      providers: Object.fromEntries(PROVIDERS.map(provider => [provider.id, {
        displayName: provider.name,
        api: 'openai-completions',
        baseURL: `https://${provider.id}.example/v1`,
        models: provider.models,
      }])),
    })
    browser = await engine.launch()
    page = await browser.newPage({ viewport: { width: 1680, height: 1000 }, locale: ZH_BROWSER_LOCALE })
    await page.addInitScript(() => {
      window.__modelMenuRectReads = 0
      // oxlint-disable-next-line typescript/unbound-method -- The wrapper supplies the original Element receiver below.
      const original = Element.prototype.getBoundingClientRect
      Element.prototype.getBoundingClientRect = function (): DOMRect {
        if (this.hasAttribute('data-menu-group') || [...this.children].some(child => child.hasAttribute('data-menu-group'))) {
          window.__modelMenuRectReads++
        }
        return original.call(this)
      }
    })
    page.on('crash', () => { crashed = true; console.error('observer test page crashed') })
    tripwire = watchConsole(page)
    await page.goto(scaffold.authenticatedUrl, { waitUntil: 'load' })
    await page.waitForSelector('[class*="frame"]', { timeout: 30_000 })
    await connectFreshWorkspaceZh(page, scaffold.workspaceCwd)
  })

  afterAll(async () => {
    try {
      await browser?.close()
    } finally {
      await scaffold?.close()
    }
  })

  // FIXME: WebKit 26.5 crashes while filtering the grouped model rows in this scenario.
  it.skipIf(engine === webkit)('keeps both pickers ordered, sticky and keyboard-operable without synchronous group rect reads', async () => {
    onTestFailed(() => saveFailureShot(page, `web-e2e-model-menu-observer-${engine.name()}`))
    let buttonMaterial: Awaited<ReturnType<typeof headingMaterial>> | undefined
    for (const entry of ['button', 'command'] as const) {
      const composer = page.locator('[data-composer-input]').first()
      const surface = entry === 'button'
        ? page.getByRole('group', { name: '模型与推理等级', exact: true })
        : page.locator('[aria-label="/model 选项"]')
      const viewport = entry === 'button'
        ? surface.getByRole('menu', { name: '模型', exact: true })
        : surface.getByRole('listbox')
      const search = entry === 'button'
        ? page.getByRole('searchbox', { name: '搜索模型…', exact: true })
        : page.getByRole('textbox', { name: '筛选选项', exact: true })
      const open = async (): Promise<void> => {
        if (entry === 'button') {
          await page.getByRole('button', { name: /^选择模型/ }).click()
          await page.getByRole('menuitem', { name: /^模型/ }).click()
        } else {
          await page.getByRole('button', { name: '添加文件或调用指令', exact: true }).click()
          await page.getByRole('option', { name: /^模型/ }).click()
        }
        await expect.poll(() => readGroups(viewport)).toEqual(EXPECTED_GROUPS)
        await expect.poll(() => search.evaluate(node => node === node.ownerDocument.activeElement)).toBe(true)
        await assertNoRectReads(page)
        await page.mouse.move(0, 0)
        await viewport.evaluate((node) => { node.style.maxHeight = '80px'; node.scrollTop = 0 })
        await assertPinned(page, viewport, -1)
      }
      const close = async (): Promise<void> => {
        if (crashed || page.isClosed() || await surface.count() === 0) return
        await search.press('Escape')
        if (entry === 'button') await page.keyboard.press('Escape')
        await surface.waitFor({ state: 'detached' })
        await composer.fill('')
      }

      await open()
      try {
        expect(await viewport.locator('[role="option"], [role="menuitemradio"]').count()).toBe(30)
        expect(await viewport.evaluate(node => node.scrollTop)).toBe(0)
        expect(await viewport.locator('[data-menu-group-heading]').evaluateAll(headings =>
          [...new Set(headings.map(heading => getComputedStyle(heading).backgroundColor))]))
          .toEqual(['rgba(0, 0, 0, 0)'])
        await scrollToGroup(viewport, 0)
        await assertPinned(page, viewport, 0)
        const material = await headingMaterial(viewport)
        expect(material.map(style => style.fill)).toEqual(['rgba(248, 249, 250, 0.94)', 'rgba(48, 49, 54, 0.94)'])
        for (const style of material) {
          expect(style).toMatchObject({ position: 'sticky', font: '11px', weight: '500', padding: '4px 7px 2px' })
        }
        if (entry === 'button') buttonMaterial = material
        else expect(material).toEqual(buttonMaterial)

        for (const index of [2, 4, 1]) {
          await scrollToGroup(viewport, index)
          await assertPinned(page, viewport, index)
          const height = await viewport.evaluate(node => node.clientHeight)
          expect(height).toBeGreaterThan(0)
          expect(height).toBeLessThanOrEqual(80)
          expect(await viewport.locator('[data-menu-group]').nth(index).evaluate(node => node.clientHeight))
            .toBeGreaterThan(height * 2)
          await scrollToGroup(viewport, index, height + 1)
          await assertPinned(page, viewport, index)
          expect(await viewport.evaluate((node, groupIndex) => {
            const headings = node.querySelectorAll('[data-menu-group-heading]')
            const firstStart = node.querySelector('[data-menu-group-start]')!
            const viewportTop = firstStart.getBoundingClientRect().top + node.scrollTop
            return Math.abs(headings[groupIndex]!.getBoundingClientRect().top - viewportTop)
          }, index)).toBeLessThan(1)
        }
        await scrollToGroup(viewport, 0, 0)
        await assertPinned(page, viewport, -1)

        for (const [key, steps] of [['ArrowDown', 18], ['ArrowUp', 10]] as const) {
          const visited = new Set<number>()
          let observedPinnedHighlight = false
          for (let step = 0; step < steps; step++) {
            const rows = viewport.locator('[role="option"], [role="menuitemradio"]')
            const before = await rows.evaluateAll(nodes => nodes.findIndex(node =>
              node.hasAttribute('data-highlighted') || node.getAttribute('aria-selected') === 'true'))
            await search.press(key)
            const expected = (before + (key === 'ArrowDown' ? 1 : -1) + 30) % 30
            await expect.poll(() => rows.evaluateAll(nodes => nodes.findIndex(node =>
              node.hasAttribute('data-highlighted') || node.getAttribute('aria-selected') === 'true'))).toBe(expected)
            await frames(page)
            await expect.poll(() => viewport.evaluate((node) => {
              const groups = [...node.querySelectorAll<HTMLElement>(':scope > [data-menu-group]')]
              const base = groups[0]!.offsetTop
              return groups.every((group) => {
                const top = group.offsetTop - base
                const shouldStick = top < node.scrollTop && top + group.offsetHeight > node.scrollTop
                return group.querySelector('[data-menu-group-heading]')!.hasAttribute('data-stuck') === shouldStick
              })
            })).toBe(true)
            const highlight = await viewport.evaluate((node) => {
              const groups = [...node.querySelectorAll<HTMLElement>(':scope > [data-menu-group]')]
              const index = groups.findIndex(group => group.querySelector('[data-highlighted], [aria-selected="true"]') !== null)
              const group = groups[index]!
              return { index, above: group.offsetTop - groups[0]!.offsetTop < node.scrollTop,
                stuck: group.querySelector('[data-menu-group-heading]')!.hasAttribute('data-stuck') }
            })
            visited.add(highlight.index)
            if (highlight.above) {
              expect(highlight.stuck).toBe(true)
              observedPinnedHighlight = true
            }
            expect(await search.evaluate(node => node === node.ownerDocument.activeElement)).toBe(true)
            await assertNoRectReads(page)
          }
          expect(visited.size).toBeGreaterThan(1)
          expect(observedPinnedHighlight).toBe(true)
        }

        await scrollToGroup(viewport, 0)
        await assertPinned(page, viewport, 0)
        await search.fill('Quartz Model 1')
        await expect.poll(() => readGroups(viewport)).toEqual([{ label: 'Quartz Gateway', rows: ['Quartz Model 1'] }])
        await assertPinned(page, viewport, -1, 1)
        expect(await viewport.evaluate(node => node.scrollTop)).toBe(0)
        await search.fill('')
        await expect.poll(() => readGroups(viewport)).toEqual(EXPECTED_GROUPS)
        await assertPinned(page, viewport, -1)
        await close()
        await open()
        expect(await search.inputValue()).toBe('')

        await scrollToGroup(viewport, 3, 90)
        await assertPinned(page, viewport, 3)
        await viewport.evaluate((node) => { node.style.maxHeight = '64px' })
        await expect.poll(() => viewport.evaluate(node => node.clientHeight)).toBeLessThanOrEqual(64)
        await assertPinned(page, viewport, 3)
        await scrollToGroup(viewport, 4, 100)
        await assertPinned(page, viewport, 4)
        await scrollToGroup(viewport, 1, 100)
        await assertPinned(page, viewport, 1)
        await scrollToGroup(viewport, 0, 0)
        await assertPinned(page, viewport, -1)
      } finally {
        await close()
      }
    }
    expect(scaffold.ctx.agentDefaultModel.currentSelection()).toEqual({ provider: 'origin-gateway', model: 'origin-large' })
    expect(tripwire.pageErrors).toEqual([])
  })

  it('keeps the model trigger compact while full names remain readable in both palettes and narrow windows', async () => {
    onTestFailed(() => saveFailureShot(page, `compact-model-button-${engine.name()}`))
    const trigger = page.getByRole('button', { name: /^选择模型/ })
    try {
      for (const dark of [false, true]) {
        await page.evaluate(value => document.body.toggleAttribute('data-ds-dark-theme', value), dark)
        for (const width of [1280, 760, 390]) {
          await page.setViewportSize({ width, height: 900 })
          const size = await trigger.evaluate(node => ({
            width: node.getBoundingClientRect().width,
            height: node.getBoundingClientRect().height,
            accessibleName: node.getAttribute('aria-label'),
          }))
          expect(size.width).toBe(40)
          expect(size.height).toBe(28)
          expect((await trigger.innerText()).trim()).toBe('')
          expect(size.accessibleName).toContain(LONG_MODEL_NAME)
          await trigger.click()
          const current = page.getByRole('menuitem', { name: /^模型/ })
          expect(await current.innerText()).toContain(LONG_MODEL_NAME)
          const value = current.locator('[data-model-id="origin-large"]')
          expect(await value.evaluate(node =>
            node.scrollWidth <= node.clientWidth || node.getBoundingClientRect().width <= node.parentElement!.clientWidth,
          )).toBe(true)
          const bounds = await current.boundingBox()
          expect(bounds!.x).toBeGreaterThanOrEqual(0)
          expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
          if (width === 1280) await saveFailureShot(page, `compact-model-current-${engine.name()}-${dark ? 'dark' : 'light'}`)
          await current.click()
          const option = page.getByRole('menuitemradio', { name: LONG_MODEL_NAME, exact: true })
          expect(await option.getAttribute('aria-checked')).toBe('true')
          expect(await option.innerText()).toBe(LONG_MODEL_NAME)
          expect(await option.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true)
          await page.keyboard.press('Escape')
          await page.keyboard.press('Escape')
          await expect.poll(() => trigger.getAttribute('aria-expanded')).toBe('false')
        }
      }
      expect(tripwire.pageErrors).toEqual([])
    } finally {
      await page.keyboard.press('Escape')
      await page.keyboard.press('Escape')
      await page.evaluate(() => { document.body.removeAttribute('data-ds-dark-theme') })
      await page.setViewportSize({ width: 1680, height: 1000 })
    }
  })

})
