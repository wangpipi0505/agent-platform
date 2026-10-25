async page => {
  const region = page.getByRole('region', {name: 'OpenAPI 导入', exact: true});
  await region.getByRole('textbox', {name: '服务基础地址', exact: true}).fill('');
  await region.getByRole('button', {name: '创建 1 个草稿'}).click();
  await region.getByRole('alert').filter({hasText: '服务地址'}).waitFor();
  await region.getByRole('textbox', {name: '服务基础地址', exact: true}).fill('http://127.0.0.1:28412');
  const code = 'openapi_ui_' + Date.now();
  for (const [name, value] of Object.entries({'技能编码': code, '技能名称': 'OpenAPI浏览器导入验证', '能力职责': '测试夹具：读取授权区域结果', '适用范围': '仅本机隔离HTTP验证', '不适用范围': '不能作为真实业务服务验收', '参数示例': 'message=browser-import；region=LOCAL', '所需角色': 'EDITOR', '返回摘要模板': '已读取授权区域结果'})) {
    await region.getByRole('textbox', {name, exact: true}).fill(value);
  }
  for (const [label, choice] of [['只读操作', '是'], ['存在副作用', '否'], ['支持幂等', '是'], ['支持取消', '否']]) {
    await region.locator('.el-form-item').filter({hasText: label}).locator('.el-select').click();
    await page.getByRole('listbox', {name: label, exact: true}).getByRole('option', {name: choice, exact: true}).click();
  }
  await region.getByRole('button', {name: '添加守卫属性'}).click();
  await region.locator('.guard-row .el-select').click();
  await page.getByRole('option', {name: 'region', exact: true}).click();
  await region.locator('.el-checkbox').filter({hasText: '已确认失败语义'}).click();
  await page.screenshot({path: '../docs/evidence/frontend-openapi-contract.png', fullPage: true});
  const wait = page.waitForResponse(r => r.url().endsWith('/admin/skills/import-openapi') && r.request().method() === 'POST');
  await region.getByRole('button', {name: '创建 1 个草稿'}).click();
  const response = await wait; const envelope = await response.json();
  if (response.status() !== 200 || envelope.data?.drafts?.[0]?.status !== 'DRAFT') throw new Error(JSON.stringify(envelope));
  await page.getByText('导入成功，已创建草稿（DRAFT）', {exact: true}).waitFor();
  await page.screenshot({path: '../docs/evidence/frontend-openapi-created.png', fullPage: true});
  await page.getByRole('button', {name: '进入详情继续验收'}).click();
  await page.waitForURL('**/#/skills/' + code);
  await page.getByText('连接测试与选型验收（发布前必需）', {exact: true}).click();
  await page.getByRole('textbox', {name: 'message', exact: true}).fill('browser-import-connection');
  await page.getByRole('textbox', {name: 'region', exact: true}).fill('LOCAL');
  const connectionWait = page.waitForResponse(r => r.url().includes('/connection-test') && r.request().method() === 'POST');
  await page.getByRole('button', {name: '发起受控测试'}).click();
  const connection = await connectionWait; const connectionBody = await connection.json();
  if (connection.status() !== 200 || !connectionBody.data?.runId) throw new Error(JSON.stringify(connectionBody));
  await page.getByText(new RegExp(connectionBody.data.runId + ' · SUCCEEDED')).waitFor({timeout: 30000});
  await page.getByRole('button', {name: '刷新测试结果'}).click();
  await page.getByText(/"ok": true/).last().waitFor();
  await page.screenshot({path: '../docs/evidence/frontend-openapi-detail.png', fullPage: true});
  return {code, status: envelope.data.drafts[0].status, selected: response.request().postDataJSON().includeOps, importSource: envelope.data.drafts[0].importSource, connectionStatus: connection.status(), connectionRunId: connectionBody.data.runId, connectionCompleted: 'SUCCEEDED', notPublished: true};
}
