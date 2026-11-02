import { describe, expect, it } from 'vitest'
import { en, formatDesktopMessage, resolveDesktopLocale, resolveDesktopStartupLocale, zh } from '../src/locale.ts'

describe('desktop locale dictionaries', () => {
  it('ships the same key set in English and Chinese', () => {
    expect(Object.keys(zh)).toEqual(Object.keys(en))
    expect(resolveDesktopLocale('zh-Hans-CN').messages).toEqual(zh)
    expect(resolveDesktopLocale('en-US').messages).toEqual(en)
    expect(resolveDesktopLocale('fr-FR').messages).toEqual(en)
  })

  it('explains the Linux keyring, update, and optional Platform limits in both languages', async () => {
    const messages = Object.fromEntries([en, zh].map((locale, index) => [index === 0 ? 'en' : 'zh-CN', {
      storage: locale.linuxSecureStorageUnavailable,
      updates: locale.updatesUnsupported,
      platformAccount: locale.platformAccountUnsupported,
    }]))
    await expect(JSON.stringify(messages, null, 2) + '\n').toMatchFileSnapshot('./expected/linux-platform-messages.json')
  })

  it('formats named values without consuming unknown placeholders', () => {
    expect(formatDesktopMessage('{name}@{version} {missing}', { name: 'plugin', version: '1.2.3' }))
      .toBe('plugin@1.2.3 {missing}')
  })

  it('prefers an explicit supported choice, then the first supported system language', () => {
    expect(resolveDesktopStartupLocale('zh', ['en-US']).id).toBe('zh-CN')
    expect(resolveDesktopStartupLocale('EN', ['zh-CN']).id).toBe('en')
    expect(resolveDesktopStartupLocale(null, ['ja-JP', 'zh-Hant', 'en-US']).id).toBe('zh-CN')
    expect(resolveDesktopStartupLocale(null, ['en-US', 'zh-CN']).id).toBe('en')
    expect(resolveDesktopStartupLocale(null, ['ja-JP']).id).toBe('en')
    expect(resolveDesktopStartupLocale(null, []).id).toBe('en')
    expect(resolveDesktopStartupLocale('ja', ['zh-CN']).id).toBe('zh-CN')
  })

})
