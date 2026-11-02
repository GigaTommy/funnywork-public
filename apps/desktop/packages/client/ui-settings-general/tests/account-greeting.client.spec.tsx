// @vitest-environment jsdom
import type { GlobalStandardProps } from '@deepseek-ai/dsh-client-ui-slots'
import { afterEach, expect, it } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import { makeTranslate } from '@deepseek-ai/dsh-client-test-runtime'
import { AccountGreeting } from '../src/client/AccountGreeting.tsx'
import { en, zh } from '../src/client/locales.ts'
const unused = (): never => { throw new Error('Greeting does not consume global hooks') }
const kit: GlobalStandardProps = {
  usePanelInfo: unused, useSessions: unused, useSessionStatus: unused,
  useSessionRetainInfo: unused, useResource: unused, useWorkspaces: unused,
  useRemoteAccess: unused, useSessionAccess: unused, useWorkspaceAccess: unused,
}
afterEach(cleanup)
it('uses the account nickname and falls back when identity is unavailable', () => {
  const t = makeTranslate(zh)
  const view = render(<AccountGreeting {...kit} t={t} useDesktopAccount={select => select({ username: 'tommy', nickname: ' 小明 ', roles: [] })} />)
  expect(view.container.textContent).toBe('HI，小明')
  view.rerender(<AccountGreeting {...kit} t={t} useDesktopAccount={select => select({ username: 'tommy', nickname: ' ', roles: [] })} />)
  expect(view.container.textContent).toBe('HI，tommy')
  view.rerender(<AccountGreeting {...kit} t={t} useDesktopAccount={select => select(undefined)} />)
  expect(view.container.textContent).toBe('HI，你好')
  view.rerender(<AccountGreeting {...kit} t={makeTranslate(en)} useDesktopAccount={select => select({ username: 'Tommy', roles: [] })} />)
  expect(view.container.textContent).toBe('HI, Tommy')
})
