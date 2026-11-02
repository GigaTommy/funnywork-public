import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import Tasks from '../../src/views/funnyworker/tasks.vue';
import { hasPermi } from '../../src/directive/permission';
import { user } from './user';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import '../../src/assets/styles/funnywork.scss';
import Requirements from '../../src/views/funnyworker/RequirementPool.vue';
import { calls, controls } from './fixture';
Object.assign(window, { ElMessage, ElMessageBox, requirementFixture: { calls, controls, user } });
const shell = new URLSearchParams(location.search).has('shell');
const app = shell
  ? createApp({ template: '<router-view />' })
  : createApp({
      components: { Requirements },
      methods: {
        viewProblem(id: string) {
          document.body.dataset.problem = id;
        },
        viewIntake(id: string) {
          document.body.dataset.intake = id;
        }
      },
      template: '<main style="padding:20px"><Requirements :can-configure="true" @view-problem="viewProblem" @view-intake="viewIntake" /></main>'
    });
if (shell) {
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: Tasks }] });
  Object.assign(window, { requirementRouter: router });
  app.use(router);
}
app.use(createI18n({ legacy: false, locale: 'zh', messages: { zh: {} } }));
app.use(ElementPlus);
app.directive('hasPermi', hasPermi);
app.mount('#app');
