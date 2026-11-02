/** Employee sign-in with native-window spacing and accessible form feedback. */
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { WelcomeApi } from '../welcome-api.ts'

export function FunnyWorkerLogin({ api }: { api: WelcomeApi }) {
  const m = api.messages
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [accepted, setAccepted] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || accepted) return
    setBusy(true); setError('')
    try {
      const result = await api.projectLogin?.(username.trim(), password)
      if (result?.ok !== true) setError(m.accountLoginFailed)
      else setAccepted(true)
    } catch { setError(m.accountConnectionFailed) }
    finally { setPassword(''); setBusy(false) }
  }
  return <main className="fw-login">
    <div className="fw-login-drag" aria-hidden="true" />
    <header className="fw-login-brand">
      <img src="assets/funnyworker-icon.png" width="88" height="88" alt="" />
      <span>{m.welcomeBrand}</span>
      <h1>{m.accountHeading}</h1>
    </header>
    <form className="fw-login-form" onSubmit={(event) => { void submit(event) }} aria-busy={busy || accepted}>
      <label htmlFor="fw-username">{m.accountLabel}</label>
      <input id="fw-username" aria-label={m.accountLabel} autoComplete="username" autoFocus placeholder={m.accountPlaceholder} value={username} onChange={(event) => { setUsername(event.target.value) }} required maxLength={80} disabled={busy || accepted} spellCheck={false} autoCapitalize="none" />
      <label htmlFor="fw-password">{m.passwordLabel}</label>
      <div className="fw-password">
        <input id="fw-password" aria-label={m.passwordLabel} type={visible ? 'text' : 'password'} autoComplete="current-password" placeholder={m.passwordPlaceholder} value={password} onChange={(event) => { setPassword(event.target.value) }} required maxLength={256} disabled={busy || accepted} aria-describedby={error ? 'fw-error' : undefined} />
        <button className="fw-password-toggle" type="button" onClick={() => { setVisible(!visible) }} aria-label={visible ? m.passwordHide : m.passwordShow} aria-pressed={visible} disabled={busy || accepted}>{visible ? m.passwordHideShort : m.passwordShowShort}</button>
      </div>
      <div id="fw-error" className="fw-login-error" role="alert">{error}</div>
      <button className="fw-login-submit" disabled={busy || accepted || !username.trim() || !password} type="submit">{(busy || accepted) && <span className="fw-login-spinner" aria-hidden="true" />}{busy || accepted ? m.welcomeAuthExchanging : m.welcomeSignIn}{!busy && !accepted && <span aria-hidden="true">→</span>}</button>
      <p className="fw-login-help">{m.accountHelp}</p>
    </form>
    <footer className="fw-login-footer" role="status" aria-live="polite"><span className="fw-login-lock" aria-hidden="true">◇</span> {accepted ? m.accountConnectionReady : busy ? m.accountConnectionBusy : error === m.accountConnectionFailed ? m.accountConnectionFailed : m.accountConnectionIdle}</footer>
  </main>
}
