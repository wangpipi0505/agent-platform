async (page) => {
  const assert = (value, message) => { if (!value) throw Error(message) };
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  const requests = [];
  let grant;
  await page.addInitScript(() => {
    window.agentPlatformPortal = {
      getAccessToken: async () => 'r39-browser-fixture',
      onSessionChanged: () => () => {},
    };
  });
  await page.route(/http:\/\/127\.0\.0\.1:25176\/(?:auth|admin)\//, async route => {
    const request = route.request();
    const method = request.method();
    const url = new URL(request.url());
    assert(request.headers().authorization === 'Bearer r39-browser-fixture', 'Missing portal bearer');
    requests.push({ path: url.pathname, method });
    let data = [];
    if (url.pathname === '/auth/session') {
      data = { tenantId: 'tenant-a', principalId: 'fixture-admin', roles: ['ADMIN'],
        expiresAt: new Date(Date.now() + 180000).toISOString(),
        capabilities: ['agent.view', 'auth.grant'], pages: ['/apps', '/platform/authorizations'] };
    } else if (url.pathname === '/admin/apps') {
      data = [{ code: 'ops', name: '运行应用' }, { code: 'other', name: '核验应用' }];
    } else if (url.pathname.endsWith('/users')) {
      assert(url.searchParams.get('q') === 'operator', 'Wrong search parameters');
      data = [{ subject: 'operator', displayName: '测试用户', active: true }];
    } else if (url.pathname.endsWith('/grants')) {
      if (method === 'PUT') {
        const body = request.postDataJSON();
        assert(body.subject === 'operator' && body.role === 'BUILDER' && body.appScope.join() === 'ops', 'Wrong scope or role');
        grant = { ...body, status: 'ACTIVE' };
        data = { saved: true };
      } else if (method === 'DELETE') {
        const body = request.postDataJSON();
        assert(body.subject === 'operator' && body.role === 'BUILDER', 'Wrong grant revoked');
        grant = { ...grant, status: 'REVOKED' };
        data = { revoked: true };
      } else {
        assert(url.searchParams.get('subject') === 'operator', 'Wrong subject selected');
        data = grant ? [grant] : [];
      }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) });
  });
  await page.goto('http://127.0.0.1:25176/?browser=' + Date.now() + '#/platform/authorizations');
  await page.getByRole('heading', { name: '检索用户' }).waitFor();
  await page.locator('#directory-keyword').fill('operator');
  await page.getByRole('button', { name: '检索' }).click();
  await page.getByRole('button', { name: '管理授权' }).click();
  await page.waitForFunction(() => document.querySelector('#grant-role')?.disabled === false);
  await page.locator('.authorization-section .el-select__wrapper').nth(0).click();
  await page.locator('.el-select-dropdown__item').filter({ hasText: '构建者' }).click();
  await page.locator('.authorization-section .el-select__wrapper').nth(1).click();
  await page.locator('.el-select-dropdown__item').filter({ hasText: '运行应用' }).click();
  await page.getByRole('heading', { name: /测试用户.*平台授权/ }).click();
  await page.getByRole('button', { name: '保存授权' }).click();
  await page.getByText('授权已保存', { exact: true }).waitFor();
  await page.getByRole('button', { name: '回收', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '回收', exact: true }).click();
  await page.getByText('已回收', { exact: true }).waitFor();
  return { grant, requests };
}
