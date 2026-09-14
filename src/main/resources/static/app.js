import { request } from './api.js';

const $ = id => document.getElementById(id);
const state = { repos: [], repo: null, authVersion: 0, repoVersion: 0, listVersion: 0 };
const statusLabels = { ACTIVE: '可用', PROVISIONING: '创建中', CLOSING: '关闭中', CLOSED: '已关闭', FAILED: '创建失败' };

function element(tag, text, className = '') {
  const node = document.createElement(tag);
  node.textContent = text;
  node.className = className;
  return node;
}

function message(id, text = '', kind = 'info') {
  $(id).textContent = text;
  $(id).hidden = !text;
  $(id).dataset.kind = kind;
}

function busy(form, value, label) {
  form.dataset.busy = String(value);
  for (const control of form.elements) control.disabled = value;
  const submit = form.querySelector('[type="submit"]');
  if (value) { submit.dataset.label = submit.textContent; submit.textContent = label; }
  else { submit.textContent = submit.dataset.label || submit.textContent; }
}

function date(value) {
  if (!value) return '—';
  // Repository timestamps are local, Session timestamps include their UTC offset.
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString('zh-CN', { hour12: false });
}

function logout(reason = '') {
  state.authVersion++;
  state.repoVersion++;
  state.listVersion++;
  state.repos = [];
  state.repo = null;
  for (const key of ['gitnova.token', 'gitnova.username', 'gitnova.pendingSessions']) sessionStorage.removeItem(key);
  document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
  $('workspace-view').hidden = true;
  $('login-view').hidden = false;
  $('repo-view').hidden = true;
  $('welcome').hidden = false;
  $('repo-list').replaceChildren();
  $('session-list').replaceChildren();
  $('password').value = '';
  message('notice');
  message('login-error', reason);
  $('username').focus();
}

function showWorkspace() {
  $('login-view').hidden = true;
  $('workspace-view').hidden = false;
  $('account-name').textContent = sessionStorage.getItem('gitnova.username') || '已登录';
  loadRepos();
}

function renderRepos() {
  const list = $('repo-list');
  list.replaceChildren();
  $('repo-count').textContent = state.repos.length;
  const query = $('repo-search').value.toLocaleLowerCase();
  const filtered = state.repos.filter(repo => repo.name.toLocaleLowerCase().includes(query));
  if (!filtered.length) {
    list.append(element('p', state.repos.length ? '没有匹配的仓库。' : '还没有仓库，点击「新建」开始。', 'small muted empty'));
  }
  for (const repo of filtered) {
    const selected = state.repo && String(state.repo.id) === String(repo.id);
    const button = element('button', '', `repo-link${selected ? ' selected' : ''}`);
    button.type = 'button';
    button.title = repo.name;
    button.setAttribute('aria-pressed', String(Boolean(selected)));
    button.append(element('span', repo.name));
    button.addEventListener('click', () => selectRepo(repo));
    list.append(button);
  }
}

async function loadRepos() {
  const version = ++state.listVersion;
  $('refresh-repos').disabled = true;
  message('notice');
  if (!state.repos.length) $('repo-list').replaceChildren(element('p', '正在读取仓库…', 'small muted empty'));
  try {
    const repos = await request('/api/repos');
    if (version !== state.listVersion) return;
    state.repos = repos;
    renderRepos();
    const selected = repos.find(repo => String(repo.id) === String(state.repo?.id));
    if (selected) await selectRepo(selected);
    else if (repos.length) await selectRepo(repos[0]);
    else {
      state.repoVersion++;
      state.repo = null;
      $('repo-view').hidden = true;
      $('welcome').hidden = false;
    }
  } catch (error) {
    if (version === state.listVersion) {
      message('notice', error.message, 'error');
      if (!state.repos.length) $('repo-list').replaceChildren(element('p', '读取失败，请刷新重试。', 'small muted empty'));
    }
  } finally { if (version === state.listVersion) $('refresh-repos').disabled = false; }
}

async function selectRepo(repo) {
  const version = ++state.repoVersion;
  state.repo = repo;
  renderRepos();
  message('notice');
  $('welcome').hidden = true;
  $('repo-view').hidden = false;
  $('repo-name').textContent = repo.name;
  $('repo-description').textContent = repo.description || '这个仓库还没有描述。';
  $('repo-visibility').textContent = Number(repo.isPrivate) === 1 ? 'Private' : 'Public';
  $('repo-id').textContent = repo.id;
  $('repo-created').textContent = date(repo.createdAt);
  // The displayed arguments contain no credentials. Use them with the existing CLI launcher.
  $('push-command').textContent = `push ${window.location.origin} ${repo.id} main`;
  $('session-count').textContent = '—';
  $('session-list').replaceChildren(element('p', '正在读取 Sessions…', 'empty'));
  $('refresh-current').disabled = true;
  try {
    const sessions = await request(`/api/repos/${encodeURIComponent(repo.id)}/agent/sessions?limit=20`);
    if (version !== state.repoVersion) return;
    renderSessions(sessions);
  } catch (error) {
    if (version !== state.repoVersion) return;
    message('notice', error.message, 'error');
    $('session-list').replaceChildren(element('p', 'Session 列表读取失败。请点击刷新重试。', 'empty'));
  } finally { if (version === state.repoVersion) $('refresh-current').disabled = false; }
}

function renderSessions(sessions) {
  $('session-count').textContent = sessions.length;
  const list = $('session-list');
  list.replaceChildren();
  if (!sessions.length) {
    const empty = element('div', '', 'empty');
    empty.append(element('h2', '开始你的第一个 Session'), element('p', '先将本地代码推送到仓库，再创建一个独立工作区。'));
    list.append(empty);
  }
  for (const session of sessions) {
    const card = element('article', '', 'session-card');
    const top = element('div', '', 'session-top');
    const status = statusLabels[session.status] ? session.status.toLowerCase() : 'unknown';
    top.append(element('span', session.sessionId, 'session-id'),
      element('span', statusLabels[session.status] || session.status, `badge status-${status}`));
    const fields = element('dl', '', 'session-fields');
    for (const [label, value] of [['Base revision', session.baseRevision], ['Workspace ID', session.workspaceId],
      ['创建时间', date(session.createdAt)], ['最后更新', date(session.updatedAt)]]) {
      const field = element('div', '');
      field.append(element('dt', label), element('dd', value || '—'));
      fields.append(field);
    }
    card.append(top, fields);
    list.append(card);
  }
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  if (form.dataset.busy === 'true') return;
  const username = $('username').value.trim();
  const password = $('password').value;
  message('login-error');
  busy(form, true, '正在登录…');
  try {
    const token = await request('/api/auth/login', { method: 'POST', form: { username, password } });
    if (typeof token !== 'string' || !token) throw new Error('登录响应缺少有效凭据。');
    sessionStorage.setItem('gitnova.token', token);
    sessionStorage.setItem('gitnova.username', username);
    $('password').value = '';
    state.authVersion++;
    showWorkspace();
  } catch (error) { message('login-error', error.message); }
  finally { busy(form, false); }
});

$('logout').addEventListener('click', () => logout());
window.addEventListener('gitnova:unauthorized', () => logout('登录已过期，请重新登录。'));
$('repo-search').addEventListener('input', renderRepos);
$('refresh-repos').addEventListener('click', loadRepos);
$('refresh-current').addEventListener('click', () => state.repo && selectRepo(state.repo));
$('new-repo').addEventListener('click', () => { message('repo-error'); $('repo-dialog').showModal(); });
$('new-session').addEventListener('click', () => {
  if (!state.repo) return;
  $('session-form').dataset.repoId = state.repo.id;
  $('session-repo-name').textContent = state.repo.name;
  message('session-error');
  $('session-dialog').showModal();
});
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => {
  const dialog = $(button.dataset.close);
  if (dialog.querySelector('form').dataset.busy !== 'true') dialog.close();
}));
document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('cancel', event => {
  if (dialog.querySelector('form').dataset.busy === 'true') event.preventDefault();
}));

$('repo-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  if (form.dataset.busy === 'true') return;
  const values = Object.fromEntries(new FormData(form));
  const authVersion = state.authVersion;
  busy(form, true, '正在创建…');
  message('repo-error');
  try {
    const repo = await request('/api/repos', { method: 'POST', form: values });
    if (authVersion !== state.authVersion) return;
    state.repo = repo;
    $('repo-dialog').close();
    form.reset();
    await loadRepos();
  } catch (error) {
    if (authVersion === state.authVersion) message('repo-error', `${error.message} 若结果不确定，请先关闭弹窗刷新仓库列表，避免重复提交。`);
  } finally { busy(form, false); }
});

$('session-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  if (form.dataset.busy === 'true') return;
  const repoId = form.dataset.repoId;
  const branchName = $('branch-name').value.trim();
  if (!branchName) { message('session-error', '请输入源分支。'); return; }
  const authVersion = state.authVersion;
  const identity = JSON.stringify([repoId, branchName]);
  busy(form, true, '正在物化工作区…');
  message('session-error');
  try {
    const pending = JSON.parse(sessionStorage.getItem('gitnova.pendingSessions') || '{}');
    const key = pending[identity] || crypto.randomUUID();
    pending[identity] = key;
    sessionStorage.setItem('gitnova.pendingSessions', JSON.stringify(pending));
    const session = await request(`/api/repos/${encodeURIComponent(repoId)}/agent/sessions`, {
      method: 'POST', body: { branchName }, key, timeout: 120000
    });
    if (authVersion !== state.authVersion) return;
    if (session.status !== 'PROVISIONING') {
      delete pending[identity];
      sessionStorage.setItem('gitnova.pendingSessions', JSON.stringify(pending));
    }
    $('session-dialog').close();
    if (String(state.repo?.id) === repoId) {
      await selectRepo(state.repo);
      message('notice', session.status === 'ACTIVE' ? 'Session 已创建，工作区已就绪。' : `Session 状态：${statusLabels[session.status] || session.status}。请刷新列表确认。`, session.status === 'FAILED' ? 'error' : 'info');
    }
  } catch (error) { if (authVersion === state.authVersion) message('session-error', error.message); }
  finally { busy(form, false); }
});

$('copy-push').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('push-command').textContent); message('notice', 'CLI 参数已复制；请在本地仓库使用现有 CLI 启动命令执行。'); }
  catch { message('notice', '浏览器不允许访问剪贴板，请手动选择并复制参数。', 'error'); }
});

if (sessionStorage.getItem('gitnova.token')) showWorkspace();
