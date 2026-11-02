// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { FunnyWorkerLogin } from '../src/client/FunnyWorkerLogin.tsx'
import { resolveDesktopLocale } from '../src/locale.ts'
import type { WelcomeApi } from '../src/welcome-api.ts'

afterEach(cleanup)
it('uses project login only and clears passwords after rejected authentication', async () => {
  const login = vi.fn(async () => ({ ok: false }))
  const api: WelcomeApi = {
    ...resolveDesktopLocale('zh'), projectLoginEnabled: true, projectLogin: login,
    analyticsEnabled: async () => false, takeNotice: async () => undefined,
    startSignIn: async () => { throw new Error('Provider login must not be used') },
    cancelSignIn: async () => { throw new Error('Provider login must not be used') },
    copySignInLink: async () => { throw new Error('Provider login must not be used') },
    saveApiKey: async () => ({ ok: false }), skip: async () => { throw new Error('Skip must not be used') },
    onAccountState: () => () => {},
  }
  render(<FunnyWorkerLogin api={api} />)
  expect(screen.queryByText(/funnypixels|127\.0\.0\.1/)).toBeNull()
  expect(screen.getByRole('status').textContent).toContain(api.messages.accountConnectionIdle)
  expect(screen.queryByText('添加 API Key')).toBeNull()
  fireEvent.change(screen.getByLabelText('账号'), { target: { value: 'employee' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'wrong-password' } })
  fireEvent.click(screen.getByRole('button', { name: '登录' }))
  await waitFor(() => { expect(screen.getByRole('alert').textContent).toContain(resolveDesktopLocale('zh').messages.accountLoginFailed) })
  expect(login).toHaveBeenCalledWith('employee', 'wrong-password')
  expect(screen.getByLabelText<HTMLInputElement>('密码').value).toBe('')
})
