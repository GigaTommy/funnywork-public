/** Personalized greeting sharing the sidebar account snapshot. */
import type { InjectFace, PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type { SettingsRootInjected } from './shell-contract.ts'

type Props = PropsRuntime<'conversation.hero.greeting'> & PropsLocale<'settings'>
  & Pick<InjectFace<SettingsRootInjected>, 'useDesktopAccount'>

/** Render the nickname, falling back to the account name or a neutral greeting. */
export function AccountGreeting({ useDesktopAccount, t }: Props) {
  const profile = useDesktopAccount(state => state)
  const name = profile?.nickname?.trim() || profile?.username.trim()
  return <>{name ? t('account.greeting', { name }) : t('account.greetingFallback')}</>
}
