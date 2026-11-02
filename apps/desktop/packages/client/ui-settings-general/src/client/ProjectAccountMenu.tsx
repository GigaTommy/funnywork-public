/** Account launcher for FunnyWork-managed identities. */
import { useRef, useState, useEffect } from 'react'
import { Menu, MenuItemButton, Tooltip, IconSettingsOutlineMedium, IconUserOutlineMedium } from '@deepseek-ai/dsh-client-ui-primitives'
import type { PropsLocale } from '@deepseek-ai/dsh-client-ui-slots'
import type { DesktopAccountProfile, DesktopModelBudget } from '../types.ts'
import css from './ProjectAccountMenu.module.css'

type Props = {
  updateAvatar: () => Promise<void>
  profile: DesktopAccountProfile
  wide: boolean
  openSettings: () => void
  signOut: () => Promise<void>
  modelBudget: (claim: boolean) => Promise<DesktopModelBudget>
  t: PropsLocale<'settings'>['t']
}

/** Display identity above settings and sign-out actions in an upward-opening menu. */
export function ProjectAccountMenu({ profile, wide, openSettings, signOut, modelBudget, updateAvatar, t }: Props) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  const [budget, setBudget] = useState<DesktopModelBudget>()
  const [budgetBusy, setBudgetBusy] = useState(false)
  const [budgetError, setBudgetError] = useState(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const refreshBudget = async (claim: boolean) => {
    if (budgetBusy) return
    setBudgetBusy(true); setBudgetError(false)
    try { const value = await modelBudget(claim); if (active.current) setBudget(value) }
    catch { if (active.current) { setBudget(undefined); setBudgetError(true) } }
    finally { if (active.current) setBudgetBusy(false) }
  }
  const money = (micros: number) => `¥${(micros / 1000000).toFixed(6)}`
  const trigger = useRef<HTMLButtonElement>(null)
  const role = profile.roles.includes('ADMIN') ? t('account.admin')
    : profile.roles.includes('OPERATOR') ? t('account.employee')
      : profile.roles.includes('VIEWER') ? t('account.viewer') : t('account.member')
  const displayName = profile.nickname?.trim() || profile.username.trim()
  const initial = Array.from(displayName)[0]?.toLocaleUpperCase()
  const logout = async () => {
    setBusy(true); setFailed(false)
    try { await signOut(); setOpen(false) }
    catch { setFailed(true) }
    finally { setBusy(false) }
  }
  return <Menu open={open} side="top" portal autoFocus className={css.root} listClassName={css.menu}
    anchor={<button ref={trigger} type="button" className={css.trigger} data-collapsed={!wide}
      title={t('account.menu')} aria-label={t('account.menu')} aria-haspopup="menu" aria-expanded={open}
      onClick={() => { if (!open) { void refreshBudget(false) } setOpen(value => !value) }}>
      <span className={css.avatar} aria-hidden="true">{profile.avatarUrl ? <img className={css.picture} src={profile.avatarUrl} alt="" /> : initial ?? <IconUserOutlineMedium size={16} />}</span>
      {wide && <span className={css.triggerName}>{displayName || t('account.fallbackName')}</span>}
    </button>} onClose={() => { setOpen(false) }}>
    <div className={css.identity}>
      <Tooltip label={t('account.changeAvatar')} side="top">
        <button type="button" className={`${css.avatar} ${css.avatarAction}`} disabled={busy}
          aria-label={t('account.changeAvatar')} aria-busy={busy}
          onClick={() => {
            setBusy(true); setFailed(false)
            void updateAvatar().catch(() => { setFailed(true) }).finally(() => { setBusy(false) })
          }}>{profile.avatarUrl ? <img className={css.picture} src={profile.avatarUrl} alt="" /> : initial ?? <IconUserOutlineMedium size={16} />}</button>
      </Tooltip>
      <div className={css.details}><strong>{displayName || t('account.fallbackName')}</strong>
        <span>{role}{profile.department ? ` · ${profile.department}` : ''}</span></div>
    </div>
    <div className={css.budget} aria-live="polite">
      <strong>{t('budget.title')}</strong>
      {budget && <>
        <span>{t('budget.remaining', { amount: money(budget.remainingMicros) })}</span>
        <span>{t('budget.held', { amount: money(budget.heldMicros) })}</span>
        <span>{t('budget.daily', { amount: money(budget.dailyRemainingMicros) })}</span>
        {(!budget.configured || !budget.enabled || budget.expiresAt <= Date.now()) && <span>{t('budget.inactive')}</span>}
        {budget.configured && budget.enabled && budget.expiresAt > Date.now() && budget.remainingMicros === 0 && <span>{t('budget.exhausted')}</span>}
        {budget.creditedMicros > 0 && <span>{t('budget.credited', { amount: money(budget.creditedMicros) })}</span>}
      </>}
      {budgetBusy && <span>{t('budget.loading')}</span>}
      {budgetError && <span role="alert">{t('budget.failed')}</span>}
    </div>
    <MenuItemButton disabled={budgetBusy} onSelect={() => { void refreshBudget(false) }}>{t('budget.refresh')}</MenuItemButton>
    {budget && budget.claimableMicros > 0 && <MenuItemButton disabled={budgetBusy} onSelect={() => { void refreshBudget(true) }}>{t('budget.claim')}</MenuItemButton>}
    <div className={css.separator} />
    <MenuItemButton icon={<IconSettingsOutlineMedium size={16} />} onSelect={() => {
      setOpen(false); trigger.current?.focus(); openSettings()
    }}>{t('trigger')}</MenuItemButton>
    <MenuItemButton icon={<IconUserOutlineMedium size={16} />} disabled={busy} onSelect={() => { void logout() }}>
      {t('account.signOut')}
    </MenuItemButton>
    {failed && <div className={css.error} role="alert">{t('account.actionFailed')}</div>}
  </Menu>
}
