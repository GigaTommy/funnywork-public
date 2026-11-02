/** Enterprise preset UI UAT through the real component and an isolated mock backend. */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
export async function runAgentBrowserUat(tab, directory) {
  const p = tab.playwright,
    results = [],
    page = p.getByTestId('management-agents');
  await mkdir(directory, { recursive: true });
  const button = (name) => page.getByRole('button', { name, exact: true });
  const audit = async () => JSON.parse(await p.getByTestId('fixture-audit').textContent());
  const ready = async () => page.getByText('企业审查助手', { exact: true }).first().waitFor({ state: 'visible', timeoutMs: 5000 });
  async function test(id, name, run) {
    try {
      await run();
      await tab.getAXState({ emit: false });
      const shot = await tab.screenshot({ fullPage: false });
      const file = id + '.jpg';
      await writeFile(join(directory, file), shot);
      results.push({ id, name, status: 'PASS', screenshot: file });
    } catch (error) {
      results.push({ id, name, status: 'FAIL', error: String(error) });
    }
  }
  await p.getByLabel('测试页面', { exact: true }).selectOption('agents');
  await p.getByRole('button', { name: '重置测试数据', exact: true }).click();
  await ready();
  await test('EP-UI-01', '当前预设及策略，无历史入口', async () => {
    assert.equal(await page.getByRole('heading', { name: '客户端策略', exact: true }).count(), 1);
    assert.equal(await button('版本历史').count(), 0);
    assert.equal(await button('创建并发布').isEnabled(), false);
  });
  await test('EP-UI-02', '编辑载入当前内容', async () => {
    await button('编辑并替换当前版本').click();
    assert.equal(await page.locator('textarea').evaluate((el) => el.value), '认真审查代码。');
  });
  await test('EP-UI-03', '模板表达式不能发布', async () => {
    await page.locator('textarea').fill('{{model}}');
    assert.equal(await button('发布新版本').isEnabled(), false);
  });
  await test('EP-UI-04', '替换发布携带当前版本', async () => {
    await page.locator('textarea').fill('只保留当前指令。');
    await button('发布新版本').click();
    await page.getByRole('status').waitFor({ state: 'visible', timeoutMs: 5000 });
    const calls = await audit(),
      call = calls.findLast((row) => row.method === 'PUT' && row.path.endsWith('/agent-presets/reviewer'));
    assert.equal(call.body.expectedVersion, 1);
    assert.equal(call.body.persona, '只保留当前指令。');
    assert.equal(await page.locator('.el-table__body tr').count(), 1);
  });
  await test('EP-UI-05', '个人预设策略默认关闭并可发布', async () => {
    const policySwitch = page.getByRole('switch').last();
    assert.equal(await policySwitch.getAttribute('aria-checked'), 'false');
    await page.locator('.el-switch').last().click();
    await button('保存策略').click();
    await page.getByRole('status').waitFor({ state: 'visible', timeoutMs: 5000 });
    assert.equal((await audit()).findLast((row) => row.path.endsWith('/agent-preset-policy')).body.allowPersonalPresets, true);
  });
  await test('EP-UI-06', '版本冲突保留草稿显示错误', async () => {
    await button('编辑并替换当前版本').click();
    await page.locator('textarea').fill('冲突时保留草稿。');
    await p.getByLabel('下一次接口', { exact: true }).selectOption('409');
    await p.getByRole('button', { name: '注入接口状态', exact: true }).click();
    await button('发布新版本').click();
    await page.getByRole('alert').waitFor({ state: 'visible', timeoutMs: 5000 });
    assert.equal(await page.locator('textarea').evaluate((el) => el.value), '冲突时保留草稿。');
  });
  await test('EP-UI-07', '失去授权清除列表及内容', async () => {
    await p.getByLabel('下一次接口', { exact: true }).selectOption('403');
    await p.getByRole('button', { name: '注入接口状态', exact: true }).click();
    await button('刷新').click();
    await page.getByRole('alert').waitFor({ state: 'visible', timeoutMs: 5000 });
    assert.equal(await page.locator('.el-table__body tr').count(), 0);
    assert.equal(await page.locator('textarea').evaluate((el) => el.value), '');
  });
  await writeFile(
    join(directory, 'results-enterprise-agents.json'),
    JSON.stringify({ scope: 'Real components with mock interfaces; no native platform or GPU acceptance', results }, null, 2)
  );
  return { passed: results.filter((row) => row.status === 'PASS').length, failed: results.filter((row) => row.status === 'FAIL').length, results };
}
