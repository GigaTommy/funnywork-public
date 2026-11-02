// Fixture wall time must not stop the native frame/task queue used by prompt admission.
import { setTimeout as wait } from 'node:timers/promises'
import { chromium, type Browser } from 'playwright'
import { expect, it } from 'vitest'
import { newEnglishPage, pinBrowserClock, pinHostClock, WEB_FIXTURE_TIME } from './support.ts'

it('advances fixture Date while preserving frame and task delivery under the Host clock', async () => {
  const hostClock = pinHostClock()
  let browser: Browser | undefined
  let unpin: (() => void) | undefined
  try {
    browser = await chromium.launch()
    const page = await newEnglishPage(browser)
    unpin = await pinBrowserClock(page)
    await page.goto('data:text/html,<title>fixture clock</title>')
    const before = await page.evaluate(() => {
      const probe = { frames: 0, tasks: 0 }
      Object.assign(globalThis, { fixtureClockProbe: probe })
      requestAnimationFrame(() => {
        probe.frames += 1
        setTimeout(() => { probe.tasks += 1 }, 0)
      })
      setTimeout(() => { probe.tasks += 1 }, 25)
      return { date: Date.now(), performance: performance.now() }
    })
    await wait(300)
    const after = await page.evaluate(() => ({
      date: Date.now(), performance: performance.now(),
      probe: Reflect.get(globalThis, 'fixtureClockProbe') as { frames: number; tasks: number },
      explicitDate: new Date('2026-09-16T00:00:00Z').toISOString(),
    }))
    expect(after.probe).toEqual({ frames: 1, tasks: 2 })
    expect(before.date).toBeGreaterThanOrEqual(WEB_FIXTURE_TIME)
    expect(after.date).toBeGreaterThan(before.date)
    expect(after.date - before.date).toBeLessThan(5_000)
    expect(after.performance - before.performance).toBeGreaterThan(0)
    expect(after.explicitDate).toBe('2026-09-16T00:00:00.000Z')
    await page.reload()
    expect(await page.evaluate(() => Date.now())).toBeGreaterThanOrEqual(after.date)
  } finally {
    try {
      unpin?.()
      await browser?.close()
    } finally {
      hostClock.mockRestore()
    }
  }
})
