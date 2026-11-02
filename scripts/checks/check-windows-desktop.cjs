/** Exercise a packaged Windows client against its configured account backend. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { parseArgs } = require('node:util');

const root = path.resolve(__dirname, '../..');
const { values } = parseArgs({ options: {
  executable: { type: 'string' }, home: { type: 'string' },
  commit: { type: 'string' }, output: { type: 'string' },
  model: { type: 'boolean', default: false },
} });
if (process.platform !== 'win32' || !values.executable || !values.commit) {
  throw new Error('Use Windows: node scripts/checks/check-windows-desktop.cjs --executable <exe> --commit <source SHA> [--home <existing signed-in home>]');
}
const output = path.resolve(values.output || path.join(root, '.local/windows-desktop-check'));
fs.mkdirSync(output, { recursive: true });
const { _electron } = require(require.resolve('playwright-core', {
  paths: [path.join(root, 'apps/desktop/node_modules/.pnpm/node_modules')],
}));

(async () => {
  const application = await _electron.launch({
    executablePath: path.resolve(values.executable), timeout: 120000,
    env: { ...process.env, DSH_HOME: path.resolve(values.home || path.join(os.homedir(), '.dsh')) },
  });
  try {
    const metadata = await application.evaluate(({ app }) => {
      const fs = process.getBuiltinModule('fs');
      const pkg = JSON.parse(fs.readFileSync(app.getAppPath() + '/package.json', 'utf8'));
      return { packaged: app.isPackaged, commit: pkg.dshBuildCommit, dirty: pkg.dshBuildDirty, version: app.getVersion() };
    });
    assert(metadata.packaged, 'Expected a packaged application');
    assert.equal(metadata.commit, values.commit, 'Package must match the reviewed source');
    await application.firstWindow();
    let main;
    for (let i = 0; i < 120; i++) {
      main = application.windows().find(page => page.url().startsWith('dsh-app://app/'));
      if (main && await main.getByRole('menuitem', { name: /^(应用|Application)$/ }).count()) break;
      if (i === 30 || i === 90) {
        const page = application.windows()[0];
        if (page) await page.screenshot({ path: path.join(output, 'startup.png') });
      }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    assert(main, 'Workspace unavailable; sign in to the configured backend before running this check');
    await main.getByRole('menuitem', { name: /^(应用|Application)$/ }).waitFor();
    await main.getByRole('button', { name: /^(账号与设置|Account and settings)$/ }).click();
    await main.getByText(/^(可用余额：|Available:)/).waitFor({ timeout: 30000 });
    await main.keyboard.press('Escape');

    for (const theme of ['light', 'dark']) {
      await application.evaluate(({ nativeTheme }, theme) => { nativeTheme.themeSource = theme; }, theme);
      await main.emulateMedia({ colorScheme: theme });
      await main.screenshot({ path: path.join(output, theme + '.png') });
    }
    await main.reload();
    await main.getByRole('menuitem', { name: /^(应用|Application)$/ }).waitFor({ timeout: 60000 });
    if (values.model) {
      const marker = 'WINDOWS_BACKEND_' + Date.now();
      await main.getByRole('button', { name: /^(新会话|New conversation)$/ }).first().click();
      await main.locator('[contenteditable=true]').first().fill('联调测试：请只回复 ' + marker + '，不要调用工具。');
      await main.getByRole('button', { name: /^(发送消息|Send message)$/ }).click();
      await main.getByText(marker, { exact: true }).last().waitFor({ timeout: 180000 });
      await main.screenshot({ path: path.join(output, 'model.png') });
      await main.reload();
      await main.getByText(marker, { exact: true }).last().waitFor({ timeout: 60000 });
    }
    // Capture callbacks instead of opening a blocking native popup. The real history
    // window still loads authenticated backend data through the production client.
    await application.evaluate(({ Menu }) => {
      Menu.prototype.popup = function(options) { globalThis.desktopCheckMenu = this; options.callback?.(); };
    });
    await main.getByRole('menuitem', { name: /^(应用|Application)$/ }).click();
    const labels = await application.evaluate(() => globalThis.desktopCheckMenu.items.map(item => item.label));
    for (const pattern of [/^(文件|File)$/, /^(对话历史|Conversation history)$/, /^(连接手机|Connect phone)$/, /^(退出账号|Sign out)/]) {
      assert(labels.some(label => pattern.test(label)), 'Missing caption action: ' + pattern);
    }
    const historyCreated = application.waitForEvent('window');
    await application.evaluate(() => globalThis.desktopCheckMenu.items.find(item => /^(对话历史|Conversation history)$/.test(item.label)).click());
    const history = await historyCreated;
    await history.getByRole('heading', { name: /^(对话历史|Conversation history)$/ }).waitFor();
    await history.getByText(/^(已保存的对话会显示在这里。|Your saved conversations will appear here\.)$/).or(history.locator('a.session')).first().waitFor({ timeout: 30000 });
    await history.screenshot({ path: path.join(output, 'history.png') });
    await history.close();
    const phoneCreated = application.waitForEvent('window');
    await application.evaluate(() => globalThis.desktopCheckMenu.items.find(item => /^(连接手机|Connect phone)$/.test(item.label)).click());
    const phone = await phoneCreated;
    await phone.locator('img[src^="data:image/png;base64,"]').waitFor({ timeout: 30000 });
    await phone.close();
    const report = { ...metadata, accountBudget: 'passed', captionActions: 'passed', rendererReload: 'passed', history: 'passed', phoneCode: 'passed', model: values.model ? 'passed' : 'not requested' };
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
  } finally { await application.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
