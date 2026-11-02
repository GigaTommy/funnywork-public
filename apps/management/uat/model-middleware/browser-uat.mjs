/** Replayable browser UAT. Invoke with a CUA Tab; no browser package or external account required. */
import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { join } from 'node:path';
const button = (p, name) => p.getByRole('button', { name, exact: true });
const text = async (p, value) => {
  const node = p.getByText(value, { exact: true }).first();
  await node.waitFor({ state: 'visible', timeoutMs: 5000 });
};
const visibleText = async (node, pattern) => {
  await node.filter({ hasText: pattern }).waitFor({ state: 'visible', timeoutMs: 5000 });
  assert.match(await node.innerText(), pattern);
};
async function selectOption(p, label, name) {
  await p.getByLabel(label, { exact: true }).click();
  await p.getByRole('option', { name, exact: true }).click();
}
/** Execute one lane, save per-case screenshot evidence and a machine-readable result. */
export async function runBrowserUat(tab, lane, directory, { ids } = {}) {
  if (lane === 'management' && (!ids || ids.length > 12))
    throw new Error('Run management UAT in explicit batches of at most 12 IDs to stay within CUA execution limits.');
  const p = tab.playwright,
    results = [],
    cases = [];
  await mkdir(directory, { recursive: true });
  let history = [];
  try {
    const previous = JSON.parse(await readFile(join(directory, 'results-' + lane + '.json'), 'utf8'));
    history = previous.history || previous.results || [];
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const add = (id, name, run) => cases.push({ id, name, run });
  const models = () => p.getByTestId('management-models');
  const usage = () => p.getByTestId('management-usage');
  const audit = async () => JSON.parse(await p.getByTestId('fixture-audit').textContent());
  async function reset(view = 'models', scenario = 'basic') {
    const dialog = p.getByRole('dialog').first();
    if ((await dialog.count()) && (await dialog.isVisible())) {
      await button(p, 'Close this dialog').click();
      await dialog.waitFor({ state: 'hidden', timeoutMs: 5000 });
    }
    await tab.getAXState({ emit: false });
    await p.getByLabel('测试页面', { exact: true }).selectOption(view);
    await p.getByLabel('测试数据', { exact: true }).selectOption(scenario);
    const generation = Number(await p.getByTestId('uat-generation').textContent()) + 1;
    await button(p, '重置测试数据').click();
    await p
      .getByTestId('uat-generation')
      .filter({ hasText: new RegExp('^' + generation + '$') })
      .waitFor({ state: 'visible', timeoutMs: 5000 });
    if (view === 'models') await text(models(), scenario === 'empty' ? '暂无记录' : '华东昇腾池');
    else
      await text(
        usage(),
        scenario === 'scope' ? '授权范围已变化，请重新查询。' : scenario === 'empty' ? '暂无记录' : 'employee-0001'
      );
    await tab.getAXState({ emit: false });
  }
  async function inject(fault) {
    await p.getByLabel('下一次接口', { exact: true }).selectOption(fault);
    await button(p, '注入接口状态').click();
  }
  async function queue(scenario = 'basic') {
    await reset('models', scenario);
    await models().getByRole('tab', { name: '实时队列', exact: true }).click();
    await text(models(), 'ticket-0001');
  }
  const pane = (name) => models().locator('#pane-' + name);
  const rows = (scope) => scope.locator('.el-table__body tr');
  const metric = (scope, label) => scope.locator('.metrics > div').filter({ hasText: label });
  if (lane === 'management') {
    add('M01', '首页加载和空必填禁用', async () => {
      await reset();
      assert.equal(await button(pane('pools'), '创建').isEnabled(), false);
      await text(models(), '华东昇腾池');
    });
    add('M02', '创建共享容量池', async () => {
      await reset();
      const f = pane('pools');
      await f.getByLabel('名称', { exact: true }).fill('UAT 昇腾资源池');
      await f.getByLabel('授权区域', { exact: true }).fill('east');
      await button(f, '创建').click();
      await text(models(), 'UAT 昇腾资源池');
      const request = (await audit()).find((r) => r.method === 'POST');
      assert.equal(request.body.concurrencyLimit, 1);
      assert.equal(request.body.queueLimit, 100);
    });
    add('M03', '编辑池策略及不可变授权区域', async () => {
      await reset();
      const f = pane('pools');
      await button(f, '编辑').click();
      assert.equal(await f.getByLabel('授权区域', { exact: true }).isEnabled(), false);
      await f.getByLabel('名称', { exact: true }).fill('华东池已编辑');
      await button(f, '保存').click();
      await text(models(), '华东池已编辑');
      assert.equal((await audit()).find((r) => r.method === 'PUT').body.expectedVersion, 1);
    });
    add('M04', '四种私有部署预设默认停用', async () => {
      await reset();
      await models().getByRole('tab', { name: '部署模型', exact: true }).click();
      const f = pane('models');
      for (const name of ['MindIE · DeepSeek', 'MindIE · Qwen', 'vLLM-Ascend · DeepSeek', 'vLLM-Ascend · Qwen']) {
        await selectOption(p, '私有部署预设', name);
        assert.equal(await f.getByLabel('名称', { exact: true }).evaluate((el) => el.value), name);
        assert.equal(await f.getByLabel('上下文上限', { exact: true }).evaluate((el) => el.value), '8192');
        assert.equal(await f.getByLabel('最大输出', { exact: true }).evaluate((el) => el.value), '2048');
        for (const label of ['工具调用', '推理模式', '图片', '流式用量（已验证）', '启用'])
          assert.equal(await f.getByRole('checkbox', { name: label, exact: true }).evaluate((el) => el.checked), false);
        assert.equal(await button(f, '创建').isEnabled(), false);
      }
    });
    add('M05', '注册停用部署并开启验证后的能力', async () => {
      await reset();
      await models().getByRole('tab', { name: '部署模型', exact: true }).click();
      const f = pane('models');
      await selectOption(p, '私有部署预设', 'MindIE · DeepSeek');
      await selectOption(p, '容量池', '华东昇腾池');
      await f.getByLabel('名称', { exact: true }).fill('UAT DeepSeek');
      await f.getByLabel('上游模型标识', { exact: true }).fill('served-deepseek');
      await f.getByLabel('服务根地址', { exact: true }).fill('https://models.invalid/v1');
      await f.getByText('工具调用', { exact: true }).click();
      await button(f, '创建').click();
      await text(models(), 'UAT DeepSeek');
      const request = (await audit()).find((r) => r.method === 'POST' && r.path.endsWith('/deployments'));
      assert.equal(request.body.enabled, false);
      assert.equal(request.body.capabilities.tools, true);
    });
    add('M06', '部署启停和复制保留能力但不启用', async () => {
      await reset();
      await models().getByRole('tab', { name: '部署模型', exact: true }).click();
      const f = pane('models'),
        row = f.getByRole('row').filter({ hasText: '企业千问' });
      await button(row, '停用').click();
      await button(row, '启用').waitFor({ state: 'visible', timeoutMs: 5000 });
      await button(row, '启用').click();
      await button(row, '停用').waitFor({ state: 'visible', timeoutMs: 5000 });
      await button(row, '复制为新部署').click();
      assert.equal(await f.getByLabel('上游模型标识', { exact: true }).evaluate((el) => el.value), 'qwen-private');
      assert.equal(await f.getByRole('checkbox', { name: '启用', exact: true }).evaluate((el) => el.checked), false);
    });
    add('M07', '取消等待票据', async () => {
      await queue();
      await button(models().getByRole('row').filter({ hasText: 'ticket-0001' }), '取消请求').click();
      await models().getByText('ticket-0001', { exact: true }).waitFor({ state: 'hidden', timeoutMs: 5000 });
      await visibleText(metric(models(), '符合筛选的请求'), /2/);
    });
    add('M08', '取消运行票据仍占用容量', async () => {
      await queue();
      const row = models().getByRole('row').filter({ hasText: 'ticket-0002' });
      await button(row, '取消请求').click();
      await visibleText(row, /停止中/);
      await visibleText(metric(models(), '占用容量'), /2\s*\/\s*2/);
    });
    add('M09', 'UNKNOWN核对必须填写依据并释放', async () => {
      await queue();
      await button(models().getByRole('row').filter({ hasText: 'ticket-0003' }), '确认停止并释放').click();
      const confirm = button(pane('queue'), '确认停止并释放').last();
      assert.equal(await confirm.isEnabled(), false);
      await models().getByLabel('确认上游已停止的核对依据', { exact: true }).fill('UAT引擎状态已确认，无活动请求');
      await confirm.click();
      await models().getByText('ticket-0003', { exact: true }).waitFor({ state: 'hidden', timeoutMs: 5000 });
      await visibleText(metric(models(), '占用容量'), /1\s*\/\s*2/);
      assert.equal(
        (await audit()).find((r) => r.path.endsWith('/reconcile')).body.evidence,
        'UAT引擎状态已确认，无活动请求'
      );
    });
    add('M10', '暂停恢复准入', async () => {
      await queue();
      await button(models(), '暂停准入').click();
      await text(models(), '准入已暂停');
      await visibleText(metric(models(), '可用容量'), /0/);
      await button(models(), '恢复准入').click();
      await button(models(), '暂停准入').waitFor({ state: 'visible', timeoutMs: 5000 });
    });
    add('M11', '队列状态筛选保留整池占用统计', async () => {
      await queue();
      await selectOption(p, '状态', '未知执行');
      await button(pane('queue'), '刷新').click();
      await text(models(), 'ticket-0003');
      assert.equal(await rows(pane('queue')).count(), 1);
      await visibleText(metric(models(), '占用容量'), /2\s*\/\s*2/);
    });
    add('M12', '1000条队列分页和条件变化保护', async () => {
      await queue('large');
      await visibleText(metric(models(), '符合筛选的请求'), /1,?000/);
      assert.equal(await rows(pane('queue')).count(), 50);
      await button(models(), '加载更多').click();
      await text(models(), 'ticket-0100');
      assert.equal(await rows(pane('queue')).count(), 100);
      const ids = await rows(pane('queue')).evaluateAll((nodes) =>
        nodes.map((n) => n.textContent.match(/ticket-\d+/)?.[0])
      );
      assert.equal(new Set(ids).size, 100);
      await models().getByLabel('成员标识', { exact: true }).fill('employee-0001');
      await text(models(), '条件已修改，请重新查询。');
      assert.equal(await button(models(), '加载更多').isEnabled(), false);
    });
    for (const [status, message] of [
      ['401', '登录已失效，请重新登录。'],
      ['403', '请核对当前权限与授权区域。'],
      ['503', '服务暂时不可用，请刷新核对操作结果。']
    ])
      add('M13-' + status, status + '错误反馈和刷新恢复', async () => {
        await reset();
        await inject(status);
        await button(models(), '刷新').click();
        await text(models(), message);
        if (status !== '503') assert.equal(await rows(pane('pools')).count(), 0);
        await button(models(), '刷新').click();
        await text(models(), '华东昇腾池');
      });
    add('M14', '版本冲突不重复写入', async () => {
      await reset();
      await button(pane('pools'), '编辑').click();
      await inject('409');
      await button(pane('pools'), '保存').click();
      await text(models(), '记录已变化，请刷新核对后再提交。');
      assert.equal((await audit()).filter((r) => r.method === 'PUT').length, 1);
    });
    add('M15', '空模型页面及创建必填', async () => {
      await reset('models', 'empty');
      await text(models(), '暂无记录');
      assert.equal(await button(pane('pools'), '创建').isEnabled(), false);
    });
    add('M16', '慢接口期间禁用重复提交', async () => {
      await reset();
      await inject('slow');
      await button(models(), '刷新').click();
      assert.equal(await button(models(), '刷新').isEnabled(), false);
      await models()
        .locator('button:not([disabled])')
        .filter({ hasText: /^刷新$/ })
        .waitFor({ state: 'visible', timeoutMs: 5000 });
      assert.equal((await audit()).filter((r) => r.path.endsWith('/pools')).length, 2);
    });
    add('M17', '队列首页五秒刷新', async () => {
      await queue();
      const before = (await audit()).filter((r) => r.path.includes('/queue/page')).length;
      await p.waitForTimeout(5200);
      assert.ok((await audit()).filter((r) => r.path.includes('/queue/page')).length > before);
    });
    add('M18', '队列加载后续页停止自动刷新', async () => {
      await queue('large');
      await button(models(), '加载更多').click();
      await text(models(), 'ticket-0100');
      const before = (await audit()).filter((r) => r.path.includes('/queue/page')).length;
      await p.waitForTimeout(5200);
      assert.equal((await audit()).filter((r) => r.path.includes('/queue/page')).length, before);
    });
    add('U01', '成员与概览计量', async () => {
      await reset('usage');
      await visibleText(metric(usage(), '已知输入 token'), /300/);
      await visibleText(metric(usage(), '已知输出 token'), /60/);
      assert.equal(await rows(usage()).count(), 2);
    });
    add('U02', '成员筛选与修改提示', async () => {
      await reset('usage');
      await usage().getByLabel('成员标识', { exact: true }).fill('employee-0001');
      await text(usage(), '条件已修改，请重新查询。');
      await button(usage(), '查询').click();
      await visibleText(metric(usage(), '已知输入 token'), /100/);
      assert.equal(await rows(usage()).count(), 1);
    });
    add('U03', '成员跳转调用明细绑定身份', async () => {
      await reset('usage');
      await button(usage().getByRole('row').filter({ hasText: '测试成员1' }), '查看调用').click();
      await text(usage(), 'PARTIAL');
      await button(usage(), '详情').waitFor({ state: 'visible', timeoutMs: 5000 });
      const request = (await audit()).findLast((r) => r.path.includes('/usage/calls'));
      assert.equal(new URL(request.path, 'https://uat.invalid').searchParams.get('owner'), 'employee-0001');
    });
    add('U04', '调用详情展示关联标识', async () => {
      await reset('usage');
      await usage().getByRole('tab', { name: '调用明细', exact: true }).click();
      await text(usage(), 'PARTIAL');
      await tab.getAXState({ emit: false });
      await button(usage(), '详情').first().click();
      await text(p, 'logical-1');
      await text(p, 'device-1');
      await text(p, '计量完整性');
    });
    add('U05', '小时热点包含计量和不完整数', async () => {
      await reset('usage');
      await usage().getByRole('tab', { name: '小时热点', exact: true }).click();
      await text(usage(), '小时（本地时间）');
      await visibleText(usage(), /300/);
      assert.equal(await rows(usage()).count(), 1);
    });
    add('U06', '任务跳转绑定成员会话任务', async () => {
      await reset('usage');
      await usage().getByRole('tab', { name: '任务与会话', exact: true }).click();
      await text(usage(), 'session-0');
      await tab.getAXState({ emit: false });
      await button(usage().getByRole('row').filter({ hasText: 'task-0' }), '查看调用').click();
      await text(usage(), 'PARTIAL');
      await button(usage(), '详情').waitFor({ state: 'visible', timeoutMs: 5000 });
      const query = new URL(
        (await audit()).findLast((r) => r.path.includes('/usage/calls')).path,
        'https://uat.invalid'
      ).searchParams;
      assert.equal(query.get('owner'), 'employee-0001');
      assert.equal(query.get('session'), 'session-0');
      assert.equal(query.get('task'), 'task-0');
    });
    add('U07', '未知用量不显示为零', async () => {
      await reset('usage', 'unknown');
      await visibleText(metric(usage(), '已知输入 token'), /未知/);
      await visibleText(metric(usage(), '已知输出 token'), /未知/);
      await visibleText(metric(usage(), '用量不完整'), /4/);
    });
    add('U08', '授权范围变化清空旧结果', async () => {
      await reset('usage', 'scope');
      await text(usage(), '授权范围已变化，请重新查询。');
      assert.equal(await rows(usage()).count(), 0);
      assert.equal(await usage().locator('.metrics').count(), 0);
    });
    add('U09', '成员分页60条无重复', async () => {
      await reset('usage', 'large');
      assert.equal(await rows(usage()).count(), 50);
      await button(usage(), '加载更多').click();
      await text(usage(), 'employee-0060');
      assert.equal(await rows(usage()).count(), 60);
      const ids = await rows(usage()).evaluateAll((nodes) =>
        nodes.map((n) => n.textContent.match(/employee-\d+/)?.[0])
      );
      assert.equal(new Set(ids).size, 60);
    });
    add('U10', '空用量结果', async () => {
      await reset('usage', 'empty');
      await text(usage(), '暂无记录');
      await visibleText(metric(usage(), '申请次数'), /0/);
    });
    add('U11', '接口故障清空报表及查询恢复', async () => {
      await reset('usage');
      await inject('503');
      await button(usage(), '查询').click();
      await text(usage(), '服务暂时不可用，请刷新核对操作结果。');
      assert.equal(await rows(usage()).count(), 0);
      await button(usage(), '查询').click();
      await text(usage(), 'employee-0001');
    });
    add('U12', '超93天日期范围返回明确提示', async () => {
      await reset('usage');
      const start = usage().locator('.el-range-input').first();
      await start.fill('2000-01-01 00:00:00');
      await start.press('Enter');
      await button(usage(), '查询').click();
      await text(usage(), '请检查筛选条件、必填项与最长 93 天的时间范围。');
    });
    add('U13', '清空筛选恢复完整成员集合', async () => {
      await reset('usage');
      await usage().getByLabel('成员标识', { exact: true }).fill('employee-0001');
      await button(usage(), '查询').click();
      await visibleText(metric(usage(), '已知输入 token'), /100/);
      await button(usage(), '清空筛选').click();
      await visibleText(metric(usage(), '已知输入 token'), /300/);
      assert.equal(await rows(usage()).count(), 2);
    });
    for (const [status, message] of [
      ['401', '登录已失效，请重新登录。'],
      ['403', '请核对当前权限与授权区域。']
    ])
      add('U14-' + status, '用量权限失效清空敏感结果', async () => {
        await reset('usage');
        await inject(status);
        await button(usage(), '查询').click();
        await text(usage(), message);
        assert.equal(await rows(usage()).count(), 0);
        assert.equal(await usage().locator('.metrics').count(), 0);
      });
    add('V01', '浅深主题及英文管理文案', async () => {
      await reset();
      await button(p, '切换外观').click();
      assert.equal(await p.locator('html').getAttribute('class'), 'dark');
      await button(p, '切换语言').click();
      await text(models(), 'Model middleware');
      await button(p, '切换语言').click();
      await button(p, '切换外观').click();
      await text(models(), '模型中间层');
    });
  } else if (lane === 'client') {
    const client = () => p.getByTestId('client-model'),
      trigger = () => client().getByRole('button');
    const resetClient = async () => {
      await button(p, '重置客户端').click();
      await trigger().waitFor({ state: 'visible', timeoutMs: 5000 });
    };
    const openModels = async () => {
      await trigger().click();
      await p.getByRole('menuitem', { name: /^模型/ }).click();
      await p.getByPlaceholder('搜索模型…').waitFor({ state: 'visible', timeoutMs: 5000 });
    };
    add('C01', '初始模型和目录展开', async () => {
      await resetClient();
      await visibleText(trigger(), /企业千问/);
      await openModels();
      await text(p, '企业私有模型');
      await text(p, '千问工具模型');
    });
    add('C02', '鼠标选择模型并提交稳定标识', async () => {
      await resetClient();
      await openModels();
      await p.getByRole('menuitemradio', { name: '企业 DeepSeek', exact: true }).click();
      await visibleText(trigger(), /企业 DeepSeek/);
      await visibleText(p.getByTestId('client-selection'), /deepseek/);
      assert.equal(JSON.parse(await p.getByTestId('client-selection').innerText()).model, 'deepseek');
    });
    add('C03', '搜索键盘选择和焦点恢复', async () => {
      await resetClient();
      await openModels();
      const search = p.getByPlaceholder('搜索模型…');
      await search.fill('千问工具');
      await search.press('Enter');
      await visibleText(trigger(), /千问工具模型/);
      assert.equal(await trigger().evaluate((el) => el === document.activeElement), true);
    });
    add('C04', '空搜索及清除恢复', async () => {
      await resetClient();
      await openModels();
      await p.getByPlaceholder('搜索模型…').fill('不存在模型XYZ');
      await text(p, '没有匹配的模型。');
      await button(p, '清除搜索').click();
      await text(p, '企业 DeepSeek');
    });
    add('C05', '推理等级选择', async () => {
      await resetClient();
      await trigger().click();
      await p.getByRole('menuitem', { name: /^推理等级/ }).click();
      await p.getByRole('menuitemradio', { name: '高', exact: true }).click();
      await visibleText(trigger(), /高/);
      await visibleText(p.getByTestId('client-selection'), /high/);
      assert.equal(JSON.parse(await p.getByTestId('client-selection').innerText()).reasoningEffort, 'high');
    });
    add('C06', '排队状态显示及结束清理', async () => {
      await resetClient();
      await button(p, '进入排队').click();
      await visibleText(client().getByRole('status'), /排队中（2）/);
      await visibleText(trigger(), /企业千问/);
      await button(p, '结束排队').click();
      assert.equal(await client().getByRole('status').count(), 0);
    });
    add('C07', '任务锁定禁止切换', async () => {
      await resetClient();
      await button(p, '切换任务锁定').click();
      assert.equal(await trigger().isEnabled(), false);
      await button(p, '切换任务锁定').click();
      assert.equal(await trigger().isEnabled(), true);
    });
    add('C08', '目录故障提示及重试恢复', async () => {
      await resetClient();
      await button(p, '目录故障').click();
      await openModels();
      await visibleText(p.getByText('模型操作失败：UAT目录暂不可用', { exact: true }), /UAT目录暂不可用/);
      await button(p, '重试').click();
      await text(p, '模型操作失败：UAT目录暂不可用');
      await button(p, '恢复接口').click();
      await openModels();
      await p.getByText('模型操作失败：UAT目录暂不可用', { exact: true }).waitFor({ state: 'hidden', timeoutMs: 5000 });
    });
    add('C09', '撤权选择错误反馈不改变当前模型', async () => {
      await resetClient();
      await button(p, '撤销模型授权').click();
      await openModels();
      await p.getByRole('menuitemradio', { name: '企业 DeepSeek', exact: true }).click();
      await visibleText(p.getByText(/模型操作失败：.*UAT模型授权已撤销/), /UAT模型授权已撤销/);
      await visibleText(trigger(), /企业千问/);
    });
    add('C10', '空授权目录', async () => {
      await resetClient();
      await button(p, '空目录').click();
      await trigger().click();
      await text(p, '没有可用的模型。');
    });
    add('C11', 'Escape逐级退出菜单', async () => {
      await resetClient();
      await openModels();
      await p.getByPlaceholder('搜索模型…').press('Escape');
      await p.getByRole('menuitem', { name: /^模型/ }).waitFor({ state: 'visible', timeoutMs: 5000 });
      await p.getByRole('menuitem', { name: /^模型/ }).press('Escape');
      assert.equal(await p.getByRole('menu', { name: '模型与推理等级', exact: true }).count(), 0);
      assert.equal(await trigger().evaluate((el) => el === document.activeElement), true);
    });
    add('V02', '客户端英文排队及深色外观', async () => {
      await resetClient();
      await button(p, '切换语言').click();
      await button(p, '进入排队').click();
      await visibleText(client().getByRole('status'), /Queued/);
      await button(p, '切换外观').click();
      assert.equal(await p.locator('html').getAttribute('class'), 'dark');
      await button(p, '切换外观').click();
      await button(p, '切换语言').click();
    });
  } else throw new Error('Unknown UAT lane');
  if (ids?.some((id) => !cases.some((c) => c.id === id))) throw new Error('Unknown UAT case ID in this lane.');
  for (const test of cases.filter((c) => !ids || ids.includes(c.id))) {
    const started = Date.now(),
      result = {
        id: test.id,
        name: test.name,
        layer: 'REAL_COMPONENT_MOCK_INTERFACE',
        startedAt: new Date().toISOString()
      };
    try {
      await test.run();
      result.status = 'PASS';
    } catch (error) {
      result.status = 'FAIL';
      result.error = String(error);
    }
    result.durationMs = Date.now() - started;
    try {
      const bytes = await tab.screenshot({ fullPage: false });
      const extension = bytes[0] === 0x89 && bytes[1] === 0x50 ? 'png' : bytes[0] === 0xff && bytes[1] === 0xd8 ? 'jpg' : null;
      if (extension === null) throw new Error('Unsupported screenshot encoding');
      const file = test.id + '-' + started + '.' + extension;
      await writeFile(join(directory, file), bytes);
      result.screenshot = file;
    } catch (error) {
      result.evidenceError = String(error);
    }
    results.push(result);
    history.push(result);
    const latest = [...new Map(history.map((r) => [r.id, r])).values()];
    await writeFile(
      join(directory, 'results-' + lane + '.json'),
      JSON.stringify(
        {
          lane,
          scope: 'Real components with mock interfaces, not full application or hardware acceptance',
          plannedCases: cases.length,
          notRun: cases.filter((c) => !latest.some((r) => r.id === c.id)).map((c) => c.id),
          results: latest,
          history
        },
        null,
        2
      )
    );
  }
  return {
    lane,
    passed: results.filter((r) => r.status === 'PASS').length,
    failed: results.filter((r) => r.status === 'FAIL').length,
    results
  };
}
