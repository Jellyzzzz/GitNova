// Run with: node --test src/test/frontend/workbench.test.cjs
// Requires Playwright + Chromium. All API responses here are isolated fixtures, not backend E2E.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

let server, browser, origin;
const root = path.resolve(__dirname, '../../main/resources/static');
const repos = [
  { id: 42, name: 'gitnova-platform', description: 'Git-native Cloud Coding Agent · 后端与工作区', isPrivate: 1, createdAt: '2026-09-14T10:00:00' },
  { id: 43, name: 'repository-research', description: 'Repository context research', isPrivate: 0 }
];
const session = {
  sessionId: 'session-7f40e8a2', workspaceId: '3fe30e61-95bb-4a1d-bd46-8ef0c03e1381', status: 'ACTIVE',
  baseRevision: 'aeed32f679ebf317ce644589c22a9a0ac7f91b242', createdAt: '2026-09-14T08:30:00Z', updatedAt: '2026-09-14T09:45:00Z'
};
async function reply(route, data, code = 200, message = 'success', headers = {}) {
  await route.fulfill({ status: code, contentType: 'application/json', headers, body: JSON.stringify({ code, message, data }) });
}

before(async () => {
  server = http.createServer(async (req, res) => {
    const file = req.url === '/' ? 'index.html' : req.url.slice(1);
    if (!['index.html', 'app.js', 'api.js', 'app.css'].includes(file)) { res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
    res.end(await fs.readFile(path.join(root, file)));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || undefined });
});
after(async () => {
  await browser?.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

async function open(t, handler, authenticated = true) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  t.after(() => context.close());
  if (authenticated) await context.addInitScript(() => {
    sessionStorage.setItem('gitnova.token', 'test-token');
    sessionStorage.setItem('gitnova.username', 'developer');
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  t.after(() => assert.deepEqual(errors, [], 'No uncaught browser errors'));
  await page.route('**/api/**', async route => {
    const req = route.request();
    if (await handler?.(route, req)) return;
    if (req.url().endsWith('/api/repos') && req.method() === 'GET') return reply(route, repos);
    if (req.method() === 'GET' && req.url().includes('/agent/sessions')) return reply(route, [session]);
    throw new Error(`Unexpected API request: ${req.method()} ${req.url()}`);
  });
  await page.goto(origin);
  return page;
}

test('login uses form data; renders repository and Session on desktop/mobile', async t => {
  const page = await open(t, async (route, req) => {
    if (!req.url().endsWith('/api/auth/login')) return false;
    assert.match(req.headers()['content-type'], /application\/x-www-form-urlencoded/);
    assert.equal(new URLSearchParams(req.postData()).get('username'), 'developer');
    await reply(route, 'test-token'); return true;
  }, false);
  if (process.env.UI_SCREENSHOT_DIR) {
    await fs.mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'login.png') });
  }
  await page.fill('#username', 'developer');
  await page.fill('#password', 'fixture-password');
  await page.click('#login-form button[type=submit]');
  await page.locator('.session-card').waitFor();
  assert.equal(await page.locator('#repo-name').textContent(), repos[0].name);
  assert.equal(await page.locator('#password').inputValue(), '');
  assert.match(await page.locator('#push-command').textContent(), /push http:\/\/127\.0\.0\.1:\d+ 42 main/);
  if (process.env.UI_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'workspace.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  if (process.env.UI_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.UI_SCREENSHOT_DIR, 'mobile.png'), fullPage: true });
  await page.click('#logout');
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gitnova.token')), null);
  assert.equal(await page.locator('#login-view').isVisible(), true);
});

test('empty list is normal; API errors are not shown as empty Sessions', async t => {
  let fail = false;
  const page = await open(t, async (route, req) => {
    if (!req.url().includes('/agent/sessions')) return false;
    await reply(route, fail ? null : [], fail ? 403 : 200, fail ? '无权访问仓库' : 'success'); return true;
  });
  await page.getByText('开始你的第一个 Session', { exact: true }).waitFor();
  assert.equal(await page.locator('#notice').isVisible(), false);
  fail = true;
  await page.click('#refresh-current');
  await page.getByText('无权访问仓库', { exact: true }).waitFor();
  assert.equal(await page.getByText('开始你的第一个 Session', { exact: true }).count(), 0);
});

test('repository creation uses form data and selects the returned repository', async t => {
  let created = false;
  const page = await open(t, async (route, req) => {
    if (!req.url().endsWith('/api/repos')) return false;
    if (req.method() === 'POST') {
      assert.equal(new URLSearchParams(req.postData()).get('name'), 'new-project');
      assert.equal(new URLSearchParams(req.postData()).get('isPrivate'), 'true');
      created = true;
      await reply(route, { ...repos[0], id: 44, name: 'new-project' });
    } else await reply(route, created ? [...repos, { ...repos[0], id: 44, name: 'new-project' }] : repos);
    return true;
  });
  await page.locator('.session-card').waitFor();
  await page.click('#new-repo');
  await page.fill('#new-repo-name', 'new-project');
  await page.click('#repo-form button[type=submit]');
  await page.locator('#repo-name').filter({ hasText: 'new-project' }).waitFor();
  assert.equal(created, true);
});

test('Session failure and reload reuse the logical request key; success releases it', async t => {
  const keys = [];
  const page = await open(t, async (route, req) => {
    if (req.method() !== 'POST') return false;
    assert.deepEqual(req.postDataJSON(), { branchName: 'main' });
    keys.push(req.headers()['idempotency-key']);
    if (keys.length === 1) await reply(route, null, 503, 'storage unavailable');
    else await reply(route, session);
    return true;
  });
  await page.locator('.session-card').waitFor();
  await page.click('#new-session');
  await page.click('#session-form button[type=submit]');
  await page.getByText('storage unavailable', { exact: true }).waitFor();
  await page.reload();
  await page.locator('.session-card').waitFor();
  await page.click('#new-session');
  await page.click('#session-form button[type=submit]');
  await page.getByText('Session 已创建，工作区已就绪。', { exact: true }).waitFor();
  assert.equal(keys.length, 2);
  assert.ok(keys[0]);
  assert.equal(keys[0], keys[1]);
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gitnova.pendingSessions')), '{}');
});

test('slow previous repository response cannot overwrite the selected repository', async t => {
  const page = await open(t, async (route, req) => {
    if (!req.url().includes('/42/agent/sessions')) return false;
    await new Promise(resolve => setTimeout(resolve, 1500));
    await reply(route, [{ ...session, sessionId: 'old-repository-session' }]); return true;
  });
  const oldResponse = page.waitForResponse(response => response.url().includes('/42/agent/sessions'));
  await page.locator('.repo-link').filter({ hasText: 'repository-research' }).click();
  await page.locator('.session-card').waitFor();
  await oldResponse;
  assert.equal(await page.locator('#repo-name').textContent(), 'repository-research');
  assert.equal(await page.locator('.session-id').textContent(), session.sessionId);
});

test('untrusted names render as text; expired auth returns to login', async t => {
  let expired = false;
  const hostileName = '<img src=x onerror=alert(1)>';
  const page = await open(t, async (route, req) => {
    if (expired) { await reply(route, null, 401, '身份认证失败'); return true; }
    if (req.url().endsWith('/api/repos')) { await reply(route, [{ ...repos[0], name: hostileName }]); return true; }
    return false;
  });
  await page.locator('.session-card').waitFor();
  assert.equal(await page.locator('#repo-name').textContent(), hostileName);
  assert.equal(await page.locator('#repo-name img').count(), 0);
  expired = true;
  await page.click('#refresh-current');
  await page.getByText('登录已过期，请重新登录。', { exact: true }).waitFor();
  assert.equal(await page.evaluate(() => sessionStorage.getItem('gitnova.token')), null);
});

test('429 presents Retry-After without automatic retries', async t => {
  let calls = 0;
  const page = await open(t, async route => {
    calls++; await reply(route, null, 429, 'limited', { 'Retry-After': '12' }); return true;
  });
  await page.getByText('请求过于频繁，请在 12 秒后重试。', { exact: true }).waitFor();
  assert.equal(calls, 1);
});
