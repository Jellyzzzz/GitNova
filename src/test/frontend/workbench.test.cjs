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
async function reply(route, data, code = 200, message = 'success', headers = {}) {
  await route.fulfill({ status: code, contentType: 'application/json', headers, body: JSON.stringify({ code, message, data }) });
}

before(async () => {
  server = http.createServer(async (req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const file = pathname === '/' ? 'index.html' : pathname.slice(1);
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
    throw new Error(`Unexpected API request: ${req.method()} ${req.url()}`);
  });
  await page.goto(origin);
  return page;
}

test('login uses form data; renders repository without retired Agent requests on desktop/mobile', async t => {
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
  await page.locator('#repo-name').waitFor({ state: 'visible' });
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

test('empty repositories are normal; request failures remain visible', async t => {
  let fail = false;
  const page = await open(t, async (route, req) => {
    if (!req.url().endsWith('/api/repos')) return false;
    await reply(route, fail ? null : [], fail ? 403 : 200, fail ? '无权访问仓库' : 'success'); return true;
  });
  await page.getByText('还没有仓库，点击「新建」开始。', { exact: true }).waitFor();
  assert.equal(await page.locator('#notice').isVisible(), false);
  fail = true;
  await page.click('#refresh-repos');
  await page.getByText('无权访问仓库', { exact: true }).waitFor();
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
  await page.locator('#repo-name').waitFor({ state: 'visible' });
  await page.click('#new-repo');
  await page.fill('#new-repo-name', 'new-project');
  await page.click('#repo-form button[type=submit]');
  await page.locator('#repo-name').filter({ hasText: 'new-project' }).waitFor();
  assert.equal(created, true);
});

test('retired Session and Task controls are absent and send no API requests', async t => {
  const requests = [];
  const page = await open(t, async (route, req) => { requests.push(req.url()); return false; });
  await page.locator('#agent-unavailable').waitFor();
  assert.match(await page.locator('#agent-unavailable').textContent(), /Agent 暂不可用/);
  for (const selector of ['#new-session', '#session-form', '#task-form']) {
    assert.equal(await page.locator(selector).count(), 0);
  }
  await page.click('#refresh-current');
  assert.equal(requests.some(url => url.includes('/agent/')), false);
});

test('repository refresh preserves a later repository selection', async t => {
  let loads = 0;
  const page = await open(t, async (route, req) => {
    if (!req.url().endsWith('/api/repos')) return false;
    loads++;
    if (loads === 2) await new Promise(resolve => setTimeout(resolve, 500));
    await reply(route, repos); return true;
  });
  await page.locator('#repo-name').waitFor({ state: 'visible' });
  const response = page.waitForResponse(value => value.url().endsWith('/api/repos'));
  await page.click('#refresh-repos');
  await page.locator('.repo-link').filter({ hasText: 'repository-research' }).click();
  await response;
  assert.equal(await page.locator('#repo-name').textContent(), repos[1].name);
});

test('untrusted names render as text; expired auth returns to login', async t => {
  let expired = false;
  const hostileName = '<img src=x onerror=alert(1)>';
  const page = await open(t, async (route, req) => {
    if (expired) { await reply(route, null, 401, '身份认证失败'); return true; }
    if (req.url().endsWith('/api/repos')) { await reply(route, [{ ...repos[0], name: hostileName }]); return true; }
    return false;
  });
  await page.locator('#repo-name').waitFor({ state: 'visible' });
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
