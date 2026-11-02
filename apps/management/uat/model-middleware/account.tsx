import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ProjectAccountMenu } from '../../../desktop/packages/client/ui-settings-general/src/client/ProjectAccountMenu';
import { zh, en } from '../../../desktop/packages/client/ui-settings-general/src/client/locales';
import type { DesktopModelBudget } from '../../../desktop/packages/client/ui-settings-general/src/types';
import '../../../desktop/packages/client/ui-theme/src/styles/base.css';
import '../../../desktop/packages/client/ui-theme/src/styles/design-platform.css';
import '../../src/assets/styles/funnywork.scss';
import './uat.css';
let claimed = false;
function App() {
  const [locale, setLocale] = useState('zh'), [dark, setDark] = useState(false), [offline, setOffline] = useState(false);
  const t = ((key: keyof typeof zh, args: Record<string, string> = {}) => Object.entries(args).reduce((s, [name, value]) => s.replaceAll('{' + name + '}', value), (locale === 'zh' ? zh : en)[key])) as React.ComponentProps<typeof ProjectAccountMenu>['t'];
  const budget = async (claim: boolean): Promise<DesktopModelBudget> => {
    if (offline) throw new Error('Mock offline');
    if (claim) claimed = true;
    return { configured: true, enabled: true, remainingMicros: claimed ? 2000000 : 1000000, heldMicros: 100000, dailyRemainingMicros: 4400000, expiresAt: 1900000000000, promotionMode: 'DAILY', claimableMicros: claimed ? 0 : 1000000, creditedMicros: claim ? 1000000 : 0 };
  };
  return <main><h1>Mac / Windows 账号额度 · 隔离模拟环境</h1><p>真实桌面账号组件；不连接真实账号或供应商。</p>
    <button onClick={() => setLocale(locale === 'zh' ? 'en' : 'zh')}>切换语言</button>
    <button onClick={() => { setDark(!dark); document.documentElement.classList.toggle('dark', !dark); document.body.toggleAttribute('data-ds-dark-theme', !dark); }}>切换外观</button>
    <button onClick={() => setOffline(!offline)}>切换断网</button>
    <div style={{ marginTop: 350, width: 280 }}><ProjectAccountMenu profile={{ username: 'Tommy', roles: ['VIEWER'] }} wide openSettings={() => {}} signOut={async () => {}} modelBudget={budget} t={t} /></div>
  </main>;
}
createRoot(document.getElementById('root')!).render(<App />);
