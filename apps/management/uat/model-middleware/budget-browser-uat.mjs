/** Real budget controls against isolated fixtures, never a paid provider. */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
export async function runBudgetBrowserUat(tab,directory) {
  const p=tab.playwright,page=p.getByTestId('management-budgets'),results=[];
  await mkdir(directory,{recursive:true});
  const button=name=>page.getByRole('button',{name,exact:true});
  const spin=name=>page.getByRole(['累计预算（元）','每日预算（元）','输入价格上界（元 / 百万 Token）','输出价格上界（元 / 百万 Token）'].includes(name)?'textbox':'spinbutton',{name,exact:true});
  const audit=async()=>JSON.parse(await p.getByTestId('fixture-audit').textContent());
  const value=async name=>Number(await spin(name).evaluate(el=>el.value));
  const saved=()=>page.getByText('已保存。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});
  async function test(id,name,action){try{await action();await tab.getAXState({emit:false});const file=id+'.jpg';await writeFile(join(directory,file),await tab.screenshot({fullPage:true}));results.push({id,name,status:'PASS',screenshot:file});}catch(e){results.push({id,name,status:'FAIL',error:String(e),screen:await page.innerText(),inputs:await page.getByRole('spinbutton').evaluateAll(els=>els.map(el=>({value:el.value,valid:el.validity.valid,message:el.validationMessage,min:el.min,step:el.step}))),lastRequests:(await audit()).slice(-3)});}}
  await p.getByLabel('测试页面',{exact:true}).selectOption('budgets');
  await p.getByRole('button',{name:'重置测试数据',exact:true}).click();
  await page.getByRole('combobox',{name:'成员',exact:true}).click();
  await p.getByRole('option',{name:'测试成员1',exact:true}).click();
  await test('BQ-UI-01','新成员默认拒绝且有效期为真实日期',async()=>{
    await page.getByText('尚未授予额度，当前不能推理。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});
    assert.equal(await value('累计预算（元）'),0);
    const date=await page.getByRole('combobox',{name:'额度有效期',exact:true}).evaluate(el=>el.value);
    assert.ok(date.startsWith(String(new Date().getFullYear())));
  });
  await test('BQ-UI-02','人民币转换、保存回显与版本递增',async()=>{
    await spin('累计预算（元）').fill('10');await spin('每日预算（元）').fill('2');
    await page.locator('.el-switch').first().click();await button('保存').click();await saved();
    const call=(await audit()).findLast(row=>row.method==='PUT'&&row.path.includes('/users/'));
    assert.equal(call.body.totalMicros,10000000);assert.equal(call.body.dailyMicros,2000000);assert.equal(call.body.enabled,true);assert.equal(call.body.expectedVersion,0);
    assert.equal(await value('累计预算（元）'),10);assert.equal(await value('每日预算（元）'),2);
    await button('保存').click();await saved();assert.equal((await audit()).findLast(row=>row.method==='PUT').body.expectedVersion,1);
  });
  await test('BQ-UI-03','全站默认暂停、零预算及保存',async()=>{
    await page.getByRole('tab',{name:'全站预算',exact:true}).click();await button('刷新').click();
    await spin('累计预算（元）').waitFor({state:'visible',timeoutMs:5000});
    assert.equal(await page.getByRole('switch').getAttribute('aria-checked'),'true');assert.equal(await value('累计预算（元）'),0);
    await spin('累计预算（元）').fill('100');await spin('每日预算（元）').fill('50');await button('保存').click();await saved();
    const call=(await audit()).findLast(row=>row.path.endsWith('/global')&&row.method==='PUT');assert.equal(call.body.totalMicros,100000000);assert.equal(call.body.paused,true);assert.equal(await value('每日预算（元）'),50);
  });
  await test('BQ-UI-04','计费未配置时拒绝，准确提交价格上界',async()=>{
    await page.getByRole('tab',{name:'模型计费',exact:true}).click();await page.getByRole('combobox',{name:'部署模型',exact:true}).click();await p.getByRole('option',{name:'企业千问 · qwen-private',exact:true}).click();
    await page.getByText('尚未配置计费，当前不能推理。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});
    await spin('输入价格上界（元 / 百万 Token）').fill('5.5');await spin('输出价格上界（元 / 百万 Token）').fill('12');await spin('提供者最大输入 Token').fill('1000000');await button('保存').click();await saved();
    const call=(await audit()).findLast(row=>row.path.includes('/prices/')&&row.method==='PUT');assert.equal(call.body.inputMicrosPerMillion,5500000);assert.equal(call.body.outputMicrosPerMillion,12000000);assert.equal(call.body.inputCeilingTokens,1000000);
  });
  await test('BQ-UI-05','并发版本冲突保留草稿',async()=>{
    await spin('输入价格上界（元 / 百万 Token）').fill('7');await spin('输入价格上界（元 / 百万 Token）').press('Tab');
    await p.getByLabel('下一次接口',{exact:true}).selectOption('409');await tab.getAXState({emit:false});await p.getByRole('button',{name:'注入接口状态',exact:true}).click();await button('保存').click();
    await page.locator('.el-alert--error').waitFor({state:'visible',timeoutMs:5000});const errorText=await page.locator('.el-alert--error').innerText();assert.ok(errorText.includes('草稿已保留'),errorText);assert.equal(await value('输入价格上界（元 / 百万 Token）'),7);
  });
  await test('BQ-UI-06','权限丢失清除计费与成员信息',async()=>{
    await p.getByLabel('下一次接口',{exact:true}).selectOption('403');await tab.getAXState({emit:false});await p.getByRole('button',{name:'注入接口状态',exact:true}).click();await button('刷新').click();
    await page.getByRole('alert').waitFor({state:'visible',timeoutMs:5000});assert.equal(await page.getByRole('button',{name:'保存',exact:true}).count(),0);assert.equal(await page.getByRole('spinbutton').count(),0);
  });
  await p.getByRole('button',{name:'重置测试数据',exact:true}).click();
  await page.getByRole('tab',{name:'全站预算',exact:true}).click();await button('刷新').click();
  await test('BQ-UI-07','深色布局',async()=>{await p.getByRole('button',{name:'切换外观',exact:true}).click();await tab.getAXState({emit:false});assert.equal(await page.getByRole('tab',{name:'全站预算',exact:true}).count(),1);});
  await test('BQ-UI-08','英文文案与深色表单',async()=>{await p.getByRole('button',{name:'切换语言',exact:true}).click();await page.getByRole('tab',{name:'Site budget',exact:true}).waitFor({state:'visible',timeoutMs:5000});assert.equal(await page.getByRole('switch',{name:'Pause all site inference',exact:true}).count(),1);});
  await p.getByRole('button',{name:'切换语言',exact:true}).click();await p.getByRole('button',{name:'切换外观',exact:true}).click();await tab.getAXState({emit:false});
  await test('BQ-UI-09','全站预算拒绝不会清空其他授权内容',async()=>{
    await p.getByLabel('下一次接口',{exact:true}).selectOption('403');await tab.getAXState({emit:false});await p.getByRole('button',{name:'注入接口状态',exact:true}).click();await button('刷新').click();
    await page.getByRole('combobox',{name:'成员',exact:true}).waitFor({state:'visible',timeoutMs:5000});
    assert.equal(await page.getByRole('tab',{name:'全站预算',exact:true}).count(),0);
    await page.getByRole('combobox',{name:'成员',exact:true}).click();await p.getByRole('option',{name:'测试成员1',exact:true}).click();
    await page.getByText('尚未授予额度，当前不能推理。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});assert.equal(await button('保存').count(),1);
    await page.getByRole('tab',{name:'模型计费',exact:true}).click();await page.getByRole('combobox',{name:'部署模型',exact:true}).click();assert.equal(await p.getByRole('option',{name:'企业千问 · qwen-private',exact:true}).count(),1);await p.getByRole('option',{name:'企业千问 · qwen-private',exact:true}).click();
  });
  await test('BQ-UI-10','普通组织管理员不能修改计费或全站预算',async()=>{
    await p.getByLabel('测试数据',{exact:true}).selectOption('budget-member-admin');await tab.getAXState({emit:false});await p.getByRole('button',{name:'重置测试数据',exact:true}).click();
    await page.getByRole('combobox',{name:'成员',exact:true}).waitFor({state:'visible',timeoutMs:5000});assert.equal(await page.getByRole('tab',{name:'全站预算',exact:true}).count(),0);
    await page.getByRole('tab',{name:'模型计费',exact:true}).click();await page.getByRole('combobox',{name:'部署模型',exact:true}).click();await p.getByRole('option',{name:'企业千问 · qwen-private',exact:true}).click();
    await page.getByText('尚未配置计费，当前不能推理。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});assert.equal(await button('保存').count(),0);assert.equal(await spin('输入价格上界（元 / 百万 Token）').isEnabled(),false);
  });
  await p.getByLabel('测试数据',{exact:true}).selectOption('basic');await tab.getAXState({emit:false});await p.getByRole('button',{name:'重置测试数据',exact:true}).click();
  await test('BQ-UI-11','一次性新用户赠送与全站发放限额',async()=>{
    await page.getByRole('tab',{name:'推广赠送',exact:true}).click();await button('刷新').click();
    const text=name=>page.getByRole('textbox',{name,exact:true});
    await text('适用租户 ID').waitFor({state:'visible',timeoutMs:5000});await text('适用租户 ID').fill('000000');
    await page.getByRole('combobox',{name:'赠送方式',exact:true}).click();await p.getByRole('option',{name:'新用户一次性赠送',exact:true}).click();
    await text('全站累计赠送上限（元）').fill('100');await text('全站每日赠送上限（元）').fill('10');await button('保存').click();await saved();
    const call=(await audit()).findLast(row=>row.path.endsWith('/promotion')&&row.method==='PUT');assert.equal(call.body.mode,'WELCOME');assert.equal(call.body.grantMicros,1000000);assert.equal(call.body.balanceCapMicros,5000000);assert.equal(call.body.totalMicros,100000000);
  });
  await test('BQ-UI-12','手工充值增加额度并提供请求去重标识',async()=>{
    await page.getByRole('tab',{name:'成员额度',exact:true}).click();await page.getByRole('combobox',{name:'成员',exact:true}).click();await p.getByRole('option',{name:'测试成员1',exact:true}).click();await spin('累计预算（元）').fill('1');await spin('每日预算（元）').fill('5');await button('保存').click();await saved();
    await page.getByRole('textbox',{name:'充值金额（元）',exact:true}).fill('2');await button('手工充值').click();await saved();
    const call=(await audit()).findLast(row=>row.path.endsWith('/topups'));assert.equal(call.body.amountMicros,2000000);assert.match(call.body.requestId,/^[a-f0-9-]{36}$/);assert.equal(await value('累计预算（元）'),3);assert.equal(await value('每日预算（元）'),5);
  });
  await test('BQ-UI-13','响应丢失后重新打开页面，核对到账且不重复充值',async()=>{
    await page.getByRole('textbox',{name:'充值金额（元）',exact:true}).fill('1');
    await p.getByLabel('下一次接口',{exact:true}).selectOption('lost-topup');await tab.getAXState({emit:false});await p.getByRole('button',{name:'注入接口状态',exact:true}).click();await button('手工充值').click();
    await button('重试未确认充值').waitFor({state:'visible',timeoutMs:5000});
    const count=(await audit()).filter(row=>row.path.endsWith('/topups')&&row.method==='POST').length;
    await p.getByRole('button',{name:'重新打开页面',exact:true}).click();await page.getByRole('combobox',{name:'成员',exact:true}).click();await p.getByRole('option',{name:'测试成员1',exact:true}).click();
    await page.getByText('已核对：充值已到账，无需重试。',{exact:true}).waitFor({state:'visible',timeoutMs:5000});assert.equal(await value('累计预算（元）'),4);assert.equal((await audit()).filter(row=>row.path.endsWith('/topups')&&row.method==='POST').length,count);
  });
  await test('BQ-UI-14','未提交的充值跨页面恢复后，用相同请求重试',async()=>{
    await p.getByLabel('下一次接口',{exact:true}).selectOption('503');await tab.getAXState({emit:false});await p.getByRole('button',{name:'注入接口状态',exact:true}).click();await button('手工充值').click();await button('重试未确认充值').waitFor({state:'visible',timeoutMs:5000});
    const before=(await audit()).findLast(row=>row.path.endsWith('/topups')).body.requestId;
    await p.getByRole('button',{name:'重新打开页面',exact:true}).click();await page.getByRole('combobox',{name:'成员',exact:true}).click();await p.getByRole('option',{name:'测试成员1',exact:true}).click();await button('重试未确认充值').waitFor({state:'visible',timeoutMs:5000});
    assert.equal(await page.getByRole('textbox',{name:'充值金额（元）',exact:true}).isEnabled(),false);await button('重试未确认充值').click();await saved();assert.equal((await audit()).findLast(row=>row.path.endsWith('/topups')).body.requestId,before);assert.equal(await value('累计预算（元）'),5);
  });
  await writeFile(join(directory,'results-model-budgets.json'),JSON.stringify({scope:'Production components with mock data; no provider spending',results},null,2));
  return {passed:results.filter(row=>row.status==='PASS').length,failed:results.filter(row=>row.status==='FAIL').length,results};
}
