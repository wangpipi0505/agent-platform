async page => {
  await page.goto('http://127.0.0.1:15173');
  await page.getByRole('button', {name: '进入开发工作台'}).click();
  await page.evaluate(() => location.hash = '/skills');
  await page.getByRole('button', {name: '注册技能', exact: true}).click();
  await page.getByText('OpenAPI 导入', {exact: true}).click();
  await page.getByRole('button', {name: '预览操作', exact: true}).click();
  await page.getByRole('alert').filter({hasText: '请粘贴文档'}).waitFor();
  await page.locator('input[type=file]').setInputFiles('tests/openapi-import.fixture.json');
  const wait = page.waitForResponse(r => r.url().includes('/import-openapi/preview') && r.request().method() === 'POST');
  await page.getByRole('button', {name: '预览操作', exact: true}).click();
  const response = await wait; const envelope = await response.json();
  if (response.status() !== 200) throw new Error(JSON.stringify(envelope));
  await page.getByText('不支持导入', {exact: true}).waitFor();
  if (!await page.getByRole('checkbox', {name: /unsupportedHead/}).isDisabled()) throw new Error('Unsupported operation selectable');
  await page.getByText('不支持导入', {exact: true}).scrollIntoViewIfNeeded();
  await page.screenshot({path: '../docs/evidence/frontend-openapi-preview.png', fullPage: true});
  await page.locator('.el-checkbox').filter({hasText: 'readResult'}).click();
  await page.getByRole('button', {name: '补充所选操作'}).click();
  return {previewStatus: response.status(), supported: envelope.data.operations.filter(o => o.supported).length, unsupported: envelope.data.operations.filter(o => !o.supported).map(o => ({operationId: o.operationId, diagnostics: o.diagnostics})), emptyDocumentBlocked: true};
}
