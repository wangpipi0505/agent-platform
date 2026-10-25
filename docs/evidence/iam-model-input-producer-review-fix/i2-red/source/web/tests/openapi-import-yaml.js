async page => {
  await page.getByRole('button', {name: '返回选择操作'}).click();
  const region = page.getByRole('region', {name: 'OpenAPI 导入', exact: true});
  const yaml = `openapi: 3.0.3
info:
  title: Text YAML browser fixture
  version: '1'
paths:
  /result:
    get:
      operationId: yamlResult
      responses:
        '200':
          description: result
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    type: string
`;
  await region.getByRole('textbox', {name: 'OpenAPI 文档文本', exact: true}).fill(yaml);
  if (await region.getByRole('group', {name: '可导入操作'}).count()) throw new Error('Changed document kept stale operations');
  const wait = page.waitForResponse(r => r.url().includes('/import-openapi/preview') && r.request().method() === 'POST');
  await region.getByRole('button', {name: '预览操作', exact: true}).click();
  const response = await wait, envelope = await response.json();
  if (response.status() !== 200 || envelope.data.operations[0]?.operationId !== 'yamlResult' || !envelope.data.operations[0]?.supported) throw new Error(JSON.stringify(envelope));
  await region.getByText('支持导入', {exact: true}).waitFor();
  await page.getByRole('button', {name: 'Close this dialog'}).click();
  return {yamlTextPreview: response.status(), supportedOperation: envelope.data.operations[0].operationId, documentEditInvalidatedPreview: true, noDraftCreated: true};
}
