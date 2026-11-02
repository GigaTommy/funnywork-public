// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { makeTranslate } from '@deepseek-ai/dsh-client-test-runtime'
import { ProjectAccountMenu } from '../src/client/ProjectAccountMenu.tsx'
import { zh } from '../src/client/locales.ts'
const wallet = { configured: true, enabled: true, remainingMicros: 1000000, heldMicros: 100000, dailyRemainingMicros: 900000, expiresAt: Date.now() + 86400000, promotionMode: 'DAILY' as const, claimableMicros: 1000000, creditedMicros: 0 }
afterEach(cleanup)
it('shows identity above actions and opens Settings from the account menu', () => {
  const openSettings = vi.fn()
  render(<ProjectAccountMenu updateAvatar={async () => {}} profile={{ username: 'tommy', nickname: ' 小明 ', roles: ['OPERATOR'], department: '研发部' }} wide
    modelBudget={async () => wallet} openSettings={openSettings} signOut={async () => {}} t={makeTranslate(zh)} />)
  expect(screen.getByText('小明')).toBeTruthy()
  expect(screen.queryByText('tommy')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '账号与设置' }))
  expect(screen.getByText('员工 · 研发部')).toBeTruthy()
  fireEvent.click(screen.getByRole('menuitem', { name: '设置' }))
  expect(openSettings).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('menu')).toBeNull()
})
it('keeps the collapsed avatar accessible and reports a failed sign-out', async () => {
  const signOut = vi.fn(async () => { throw new Error('offline') })
  render(<ProjectAccountMenu updateAvatar={async () => {}} profile={{ username: 'tommy', roles: ['VIEWER'] }} wide={false}
    modelBudget={async () => wallet} openSettings={() => {}} signOut={signOut} t={makeTranslate(zh)} />)
  fireEvent.click(screen.getByRole('button', { name: '账号与设置' }))
  expect(screen.getByText('只读成员')).toBeTruthy()
  fireEvent.click(screen.getByRole('menuitem', { name: '退出登录' }))
  await waitFor(() => { expect(screen.getByRole('alert').textContent).toContain('暂时无法完成，请重试') })
  expect(signOut).toHaveBeenCalledTimes(1)
})

it('reads the managed allowance on opening and claims credit without exposing credentials', async () => {
  const modelBudget = vi.fn(async (claim: boolean) => claim
    ? { ...wallet, remainingMicros: 2000000, claimableMicros: 0, creditedMicros: 1000000 }
    : wallet)
  render(<ProjectAccountMenu updateAvatar={async () => {}} profile={{ username: 'Tommy', roles: ['VIEWER'] }} wide openSettings={() => {}} signOut={async () => {}} modelBudget={modelBudget} t={makeTranslate(zh)} />)
  fireEvent.click(screen.getByRole('button', { name: '账号与设置' }))
  await screen.findByText('可用余额：¥1.000000')
  fireEvent.click(screen.getByRole('menuitem', { name: '领取额度' }))
  await screen.findByText('已领取：¥1.000000')
  expect(screen.getByText('可用余额：¥2.000000')).toBeTruthy()
  expect(modelBudget.mock.calls.map(call => call[0])).toEqual([false, true])
  expect(screen.queryByRole('menuitem', { name: '领取额度' })).toBeNull()
  expect(screen.getByRole('menu').textContent).toMatchInlineSnapshot('"T更换头像Tommy只读成员模型额度可用余额：¥2.000000已预留：¥0.100000今日可用：¥0.900000已领取：¥1.000000刷新额度设置退出登录"')
})
it('clears stale allowance and offers refresh after a failed read', async () => {
  const modelBudget = vi.fn(async () => wallet).mockRejectedValueOnce(new Error('private server diagnostic'))
  render(<ProjectAccountMenu updateAvatar={async () => {}} profile={{ username: 'Tommy', roles: [] }} wide openSettings={() => {}} signOut={async () => {}} modelBudget={modelBudget} t={makeTranslate(zh)} />)
  fireEvent.click(screen.getByRole('button', { name: '账号与设置' }))
  await screen.findByText('额度读取失败，请刷新重试。')
  expect(screen.queryByText('private server diagnostic')).toBeNull()
  fireEvent.click(screen.getByRole('menuitem', { name: '刷新额度' }))
  await screen.findByText('可用余额：¥1.000000')
})

it('shows a saved picture and offers a retry when choosing a replacement fails', async () => {
  const updateAvatar = vi.fn(async () => { throw new Error('private upload detail') })
  const avatarUrl = 'data:image/png;base64,AAAA'
  const view = render(<ProjectAccountMenu profile={{ username: 'Gigi', avatarUrl, roles: [] }} wide updateAvatar={updateAvatar}
    modelBudget={async () => wallet} openSettings={() => {}} signOut={async () => {}} t={makeTranslate(zh)} />)
  expect(view.container.querySelector('img')?.getAttribute('src')).toBe(avatarUrl)
  fireEvent.click(screen.getByRole('button', { name: '账号与设置' }))
  expect(screen.queryByRole('menuitem', { name: '更换头像' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '更换头像' }))
  await waitFor(() => { expect(updateAvatar).toHaveBeenCalledTimes(1) })
  await waitFor(() => { expect(screen.getByRole('alert').textContent).toBe('暂时无法完成，请重试') })
  expect(view.container.querySelector('img')?.getAttribute('src')).toBe(avatarUrl)
})
