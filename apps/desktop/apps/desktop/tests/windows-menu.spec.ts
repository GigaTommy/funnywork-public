import { expect, it } from 'vitest'
import { windowsApplicationMenu } from '../src/windows-menu.ts'
import { resolveDesktopLocale } from '../src/locale.ts'

it.each(['zh', 'en'])('exposes account actions in the Windows caption popup (%s)', (locale) => {
  const m = resolveDesktopLocale(locale).messages
  const history = { label: m.historyTitle, click: () => undefined }
  const phone = { label: m.deviceLinkTitle, click: () => undefined }
  const signOut = { label: m.accountSignOut, click: () => undefined }
  const file = { label: m.fileMenu, submenu: [{ label: m.closePage }] }
  const application = [{ label: m.aboutMenu }, { label: m.checkUpdatesMenu }, { label: m.exitApplication }]
  const items = windowsApplicationMenu(file, [history, phone, signOut], application)
  expect(items).toContain(history)
  expect(items).toContain(phone)
  expect(items).toContain(signOut)
  expect(items).toMatchSnapshot()
  expect(windowsApplicationMenu(file, [], application).map(item => item.label)).toEqual([
    m.fileMenu, undefined, m.aboutMenu, m.checkUpdatesMenu, m.exitApplication,
  ])
})
