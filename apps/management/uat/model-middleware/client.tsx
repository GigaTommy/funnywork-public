import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ModelSelect } from '../../../desktop/packages/client/ui-model-selection/src/client/ModelSelect';
import { zh, en } from '../../../desktop/packages/client/ui-model-selection/src/client/locales';
import '../../../desktop/packages/client/ui-theme/src/styles/base.css';
import '../../../desktop/packages/client/ui-theme/src/styles/design-platform.css';
import '../../src/assets/styles/funnywork.scss';
import './uat.css';
const groups = [
  {
    id: 'enterprise-qwen',
    name: '企业私有模型',
    models: [
      {
        id: 'qwen',
        name: '企业千问',
        reasoning: {
          efforts: [
            { id: 'off', name: '关闭' },
            { id: 'high', name: '高' }
          ],
          defaultEffort: 'off'
        }
      },
      { id: 'deepseek', name: '企业 DeepSeek' },
      { id: 'qwen-long', name: '千问长上下文' },
      { id: 'deepseek-small', name: 'DeepSeek 小模型' },
      { id: 'qwen-tools', name: '千问工具模型' }
    ]
  }
];
const initial = () => ({
  current: { provider: 'enterprise-qwen', model: 'qwen' },
  routable: true,
  groups: structuredClone(groups),
  failures: [],
  status: 'ready',
  pending: null,
  error: null
});
let state: any = initial(),
  failure = false,
  rejectSelection = false;
const subscribers = new Set<() => void>();
const store: any = {
  getSnapshot: () => state,
  subscribe: (listener: () => void) => {
    subscribers.add(listener);
    return () => subscribers.delete(listener);
  },
  set: (value: any) => {
    state = value;
    subscribers.forEach((listener) => listener());
  }
};
function patch(value: any) {
  store.set({ ...state, ...value });
}
function App() {
  const [locked, setLocked] = useState(false),
    [locale, setLocale] = useState('zh'),
    [dark, setDark] = useState(false),
    [audit, setAudit] = useState(''),
    [generation, setGeneration] = useState(0);
  const dictionary: any = locale === 'zh' ? zh : en;
  const t: any = (key: string, params?: any) =>
    String(dictionary[key] ?? ({ unknown: '未知', retry: '重试' } as any)[key] ?? key).replace(/\{(\w+)\}/g, (m, k) =>
      params?.[k] === undefined ? m : String(params[k])
    );
  function reset() {
    failure = false;
    rejectSelection = false;
    setLocked(false);
    store.set(initial());
    setAudit('');
    setGeneration((value) => value + 1);
  }
  function load() {
    if (failure) patch({ status: 'error', error: 'UAT目录暂不可用' });
    else if (state.status === 'error') patch({ status: 'ready', error: null, groups: structuredClone(groups) });
  }
  async function select(selection: any) {
    if (rejectSelection)
      return { ok: false, error: { code: 'session/model-unavailable', message: 'UAT模型授权已撤销' } };
    patch({ pending: selection });
    await new Promise((resolve) => setTimeout(resolve, 150));
    patch({ current: selection, pending: null });
    setAudit(JSON.stringify(selection));
    return { ok: true, value: undefined };
  }
  return (
    <main>
      <header>
        <h1>Harness 客户端模型选择 UAT</h1>
        <p>真实 ModelSelect 组件。目录与状态为测试注入，不代表完整 Electron / Agent 联调。</p>
      </header>
      <nav aria-label="客户端UAT控制台">
        <button onClick={reset}>重置客户端</button>
        <button onClick={() => patch({ admissionWaiting: 2 })}>进入排队</button>
        <button onClick={() => patch({ admissionWaiting: undefined })}>结束排队</button>
        <button onClick={() => setLocked(!locked)}>切换任务锁定</button>
        <button
          onClick={() => {
            failure = true;
            patch({ status: 'error', error: 'UAT目录暂不可用' });
          }}
        >
          目录故障
        </button>
        <button
          onClick={() => {
            failure = false;
          }}
        >
          恢复接口
        </button>
        <button
          onClick={() => {
            rejectSelection = true;
          }}
        >
          撤销模型授权
        </button>
        <button onClick={() => patch({ groups: [], current: null, routable: false })}>空目录</button>
        <button
          onClick={() => {
            setDark(!dark);
            document.documentElement.classList.toggle('dark', !dark);
            document.body.dataset.theme = dark ? 'light' : 'dark';
          }}
        >
          切换外观
        </button>
        <button onClick={() => setLocale(locale === 'zh' ? 'en' : 'zh')}>切换语言</button>
        <a href="/">管理端UAT</a>
      </nav>
      <section className="client-card" data-testid="client-model">
        <h2>会话模型</h2>
        <ModelSelect key={generation} locked={locked} available directory={store} load={load} select={select} t={t} />
      </section>
      <p>选择提交记录：</p>
      <output data-testid="client-selection">{audit}</output>
    </main>
  );
}
createRoot(document.getElementById('app')!).render(<App />);
