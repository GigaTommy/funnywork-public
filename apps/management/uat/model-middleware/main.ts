import { createApp, ref } from 'vue';
import {createPinia} from 'pinia';
import {useUserStore} from '../../src/store/modules/user';
import { createI18n } from 'vue-i18n';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import '../../src/assets/styles/funnywork.scss';
import './uat.css';
import Budgets from '../../src/views/funnyworker/ModelBudgets.vue';
import Models from '../../src/views/funnyworker/ModelMiddleware.vue';
import Agents from '../../src/views/funnyworker/EnterpriseAgentPresets.vue';
import Usage from '../../src/views/funnyworker/ModelMiddlewareUsage.vue';
import { resetFixture, auditText, fixtureRevision, setFault } from './fixture';
const i18n = createI18n({ legacy: false, locale: 'zh_CN', messages: { zh_CN: {}, en: {} } });
const pinia=createPinia();
// Synthetic permissions for this isolated fixture only; real-shell UAT uses identity-service roles.
useUserStore(pinia).permissions=['funnyworker:models:read','funnyworker:models:write','funnyworker:usage:read'];
const app = createApp({
  components: { Models, Usage, Agents, Budgets },
  setup() {
    const view = ref('models'),
      scenario = ref('basic'),
      generation = ref(0),
      dark = ref(false),
      fault = ref('none');
    function reset() {
      resetFixture(scenario.value);
      generation.value++;
    }
    function theme() {
      dark.value = !dark.value;
      document.documentElement.classList.toggle('dark', dark.value);
    }
    function language() {
      i18n.global.locale.value = i18n.global.locale.value === 'en' ? 'zh_CN' : 'en';
    }
    function injectFault() {
      setFault(fault.value);
    }
    function reopen() { generation.value++; }
    return { reopen, view, scenario, generation, dark, theme, reset, language, fault, injectFault, auditText, fixtureRevision };
  },
  template: `<main>
 <header><h1>模型接入 UAT · 隔离模拟环境</h1><p>真实页面组件 + 可控模拟接口。不会连接公网模型或生产数据。</p></header>
 <nav aria-label="UAT控制台">
  <label>测试页面 <select aria-label="测试页面" v-model="view"><option value="budgets">模型限额</option><option value="models">管理模型与队列</option><option value="usage">管理用量</option><option value="agents">企业 Agent 预设</option></select></label>
  <label>测试数据 <select aria-label="测试数据" v-model="scenario"><option value="basic">基础</option><option value="large">1000条队列 / 60名成员</option><option value="unknown">未知用量</option><option value="empty">空数据</option><option value="scope">授权范围变化</option><option value="budget-member-admin">普通组织管理员</option></select></label>
  <button @click="reset">重置测试数据</button><button @click="reopen">重新打开页面</button><span>测试轮次 <output data-testid="uat-generation">{{generation}}</output></span><button @click="theme">切换外观</button><button @click="language">切换语言</button>
  <label>下一次接口 <select aria-label="下一次接口" v-model="fault"><option value="none">正常</option><option value="401">401登录失效</option><option value="403">403无权限</option><option value="409">409版本冲突</option><option value="503">503不可用</option><option value="slow">延迟800毫秒</option><option value="lost-topup">充值已提交但响应丢失</option></select></label><button @click="injectFault">注入接口状态</button>
  <a href="/client.html">客户端模型选择 UAT</a>
 </nav>
 <section data-testid="management-models" v-if="view==='models'"><Models :key="generation"/></section>
 <section data-testid="management-agents" v-else-if="view==='agents'"><Agents :key="generation"/></section>
 <section data-testid="management-budgets" v-else-if="view==='budgets'"><Budgets :key="generation"/></section>
 <section data-testid="management-usage" v-else><Usage :key="generation"/></section>
 <details><summary>模拟接口审计（仅测试数据）</summary><pre data-testid="fixture-audit" :data-revision="fixtureRevision">{{auditText}}</pre></details>
</main>`
});
app.use(pinia);
app.use(ElementPlus);
app.use(i18n);
app.mount('#app');
