/** Built home page identity, with display-only desktop account metadata. */
import { mkdir, mkdtemp, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { expect, it, onTestFinished } from 'vitest'
import { launchWebScaffold } from './scaffold.ts'

it('distinguishes personal identity from navigation in light and dark home layouts', async () => {
  const build: unknown = JSON.parse(await readFile(fileURLToPath(new URL('../../../.dsh-build/client-build-environment.json', import.meta.url)), 'utf8'))
  if (typeof build !== 'object' || build === null || !('environment' in build)
    || typeof build.environment !== 'object' || build.environment === null) {
    throw new Error('client build record must carry its environment')
  }
  const funnyWork = 'DSH_CLIENT_TITLE' in build.environment && build.environment.DSH_CLIENT_TITLE === 'FunnyWork'
  const scaffold = await launchWebScaffold({})
  onTestFinished(() => scaffold.close())
  const browser = await chromium.launch()
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' })
    await page.addInitScript(() => {
      Object.assign(window, { dshDesktop: { protocolVersion: 1, account: {
        profile: async () => ({ username: 'tommy', nickname: '小明', roles: ['OPERATOR'] }),
        signOut: async () => {},
      } } })
    })
    await page.goto(scaffold.authenticatedUrl, { waitUntil: 'load' })
    await page.getByText('HI，小明', { exact: true }).waitFor()
    expect(await page.getByText('今天想做点什么？', { exact: true }).count()).toBe(1)
    expect(await page.locator('[class*=headline]').innerText()).toBe(
      'HI，小明\n今天想做点什么？' + (funnyWork ? '' : '\n预览版'),
    )
    expect(await page.locator('[data-slot="sidebar.brand.mark"], [data-slot="conversation.hero.brand.mark"]').count()).toBe(0)
    const account = page.getByRole('button', { name: '账号与设置', exact: true })
    expect(await account.textContent()).toContain('小明')
    const root = fileURLToPath(new URL('../../../.artifacts/home-identity/', import.meta.url))
    await mkdir(root, { recursive: true })
    const dir = await mkdtemp(join(root, 'run-'))
    for (const colorScheme of ['light', 'dark'] as const) {
      await page.emulateMedia({ colorScheme })
      await page.screenshot({ path: join(dir, `${colorScheme}.png`), animations: 'disabled' })
    }
    await page.getByRole('button', { name: '收起侧边栏', exact: true }).click()
    const expand = page.getByRole('button', { name: '打开侧边栏', exact: true })
    await expand.waitFor()
    await page.mouse.move(700, 650)
    await expect.poll(() => account.textContent()).toBe('小')
    await expect.poll(async () => Math.round((await expand.boundingBox())?.x ?? -1)).toBe(10)
    expect(await expand.locator('svg').isVisible()).toBe(true)
    await page.screenshot({ path: join(dir, 'collapsed.png'), animations: 'disabled' })
    await expand.click()
    await expect.poll(() => account.textContent()).toContain('小明')
    await page.getByRole('button', { name: '收起侧边栏', exact: true }).waitFor()
    await account.click()
    await page.getByRole('menuitem', { name: '设置', exact: true }).waitFor()
    await page.keyboard.press('Escape')
    await page.setViewportSize({ width: 800, height: 600 })
    await page.mouse.move(700, 550)
    await page.reload({ waitUntil: 'load' })
    await page.getByText('HI，小明', { exact: true }).waitFor()
    await account.waitFor()
    await page.screenshot({ path: join(dir, 'compact.png'), animations: 'disabled' })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  } finally {
    await browser.close()
  }
})
