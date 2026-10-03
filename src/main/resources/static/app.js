import { request } from './api.js';

const $ = id => document.getElementById(id);
const state = { repos: [], repo: null, authVersion: 0, repoVersion: 0, listVersion: 0, conversation: null, taskTimer: null };
const statusLabels = { ACTIVE: '可用', PROVISIONING: '创建中', CLOSING: '关闭中', CLOSED: '已关闭', FAILED: '创建失败' };
const settledTaskStatuses = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_USER']);
const taskStatusLabels = { ACTIVE: '排队 / 执行中', COMPLETED: '已返回', FAILED: '执行失败', CANCELLED: '已取消', WAITING_USER: '需要用户介入' };

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
  clearTimeout(state.taskTimer);
  state.conversation = null;
  state.authVersion++;
  state.repoVersion++;
  state.listVersion++;
  state.repos = [];
  state.repo = null;
  for (const key of ['gitnova.token', 'gitnova.username', 'gitnova.pendingSessions']) sessionStorage.removeItem(key);
  for (const key of Object.keys(sessionStorage)) if (key.startsWith('gitnova.tasks.')) sessionStorage.removeItem(key);
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
  const repo = { id: state.repo.id, name: state.repo.name };
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
    const open = element('button', '进入 Session →', 'button');
    open.type = 'button';
    open.disabled = session.status !== 'ACTIVE';
    open.addEventListener('click', () => openTaskConversation(repo, session));
    card.append(top, fields, open);
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

function openTaskConversation(repo, session) {
  clearTimeout(state.taskTimer);
  const view = {
    repoId: repo.id, sessionId: session.sessionId, authVersion: state.authVersion,
    storageKey: `gitnova.tasks.${sessionStorage.getItem('gitnova.username')}.${repo.id}.${session.sessionId}`,
    entries: [], submitting: false, polling: false
  };
  state.conversation = view;
  message('task-error');
  try {
    const saved = JSON.parse(sessionStorage.getItem(view.storageKey) || '[]');
    if (!Array.isArray(saved) || saved.some(entry => !entry || typeof entry.key !== 'string'
      || typeof entry.message !== 'string' || (entry.taskId != null && typeof entry.taskId !== 'string'))) {
      throw new Error('Invalid task references');
    }
    // Only request identities are cached. Answers and lifecycle state are read from the server again.
    view.entries = saved;
  } catch {
    message('task-error', '本标签页的任务引用无法读取，请勿盲目重复提交。');
    view.storageUnavailable = true;
  }
  $('task-dialog-title').textContent = `${repo.name} · Session`;
  $('task-session-meta').textContent = `Session ${session.sessionId} · Workspace ${session.workspaceId}`;
  $('task-message').value = view.entries.find(entry => !entry.taskId)?.message || '';
  renderTaskConversation(view);
  $('task-dialog').showModal();
  pollTaskConversation(view);
}

function saveTaskReferences(view) {
  try {
    sessionStorage.setItem(view.storageKey, JSON.stringify(view.entries.map(({ key, message, taskId, runId }) =>
      ({ key, message, taskId, runId }))));
    return true;
  } catch {
    view.storageUnavailable = true;
    message('task-error', '浏览器无法保存请求标识。请保留当前窗口与 Task ID，避免重复提交。');
    return false;
  }
}

function renderTaskConversation(view) {
  if (state.conversation !== view) return;
  const list = $('task-messages');
  list.replaceChildren();
  if (!view.entries.length) list.append(element('p', '在这里描述一个任务。Agent 将读取当前 Workspace，并返回执行结果。', 'empty muted'));
  for (const entry of view.entries) {
    const card = element('article', '', 'task-exchange');
    const header = element('div', '', 'task-exchange-header');
    header.append(element('strong', '你'), element('span', taskStatusLabels[entry.status]
      || (entry.taskId ? '查询中' : '提交待确认'), `badge ${entry.status === 'COMPLETED' ? 'status-active' : ''}`));
    card.append(header, element('p', entry.message, 'task-user-message'));
    if (entry.taskId) card.append(element('p', `Task ${entry.taskId}${entry.runId ? ` · Run ${entry.runId}` : ''}`, 'small muted task-meta'));
    if (entry.answer) {
      card.append(element('strong', 'GitNova'), element('pre', entry.answer, 'task-answer'));
    } else if (settledTaskStatuses.has(entry.status)) {
      card.append(element('p', entry.status === 'WAITING_USER'
        ? '本次执行需要用户介入，尚无最终回答。自动恢复入口暂未接入。'
        : '服务端尚未返回最终 answer，不能将状态标记当作任务验收结果。', 'small muted'));
    }
    if (entry.terminalReason) card.append(element('p', `结束原因：${entry.terminalReason}`, 'small muted'));
    list.append(card);
  }
  const pending = view.entries.find(entry => !entry.taskId);
  const active = view.entries.some(entry => entry.taskId && !settledTaskStatuses.has(entry.status));
  $('task-form').dataset.busy = String(view.submitting);
  $('task-message').readOnly = Boolean(pending) || active || view.submitting || view.storageUnavailable;
  $('send-task').disabled = view.submitting || active || view.storageUnavailable;
  $('send-task').textContent = view.submitting ? '正在提交…' : pending ? '重试原请求' : '发送任务';
  $('refresh-task').disabled = view.polling || view.submitting || !view.entries.some(entry => entry.taskId);
  $('task-progress').textContent = active ? '任务已提交，正在读取服务端状态…'
    : pending ? '提交结果尚未确认。重试会沿用同一幂等键，不创建第二个逻辑任务。'
    : view.entries.length ? '回答来自已持久化的模型输出；正常结束不等于独立验收通过。' : '准备就绪';
}

async function pollTaskConversation(view, refreshAll = false) {
  if (state.conversation !== view || !$('task-dialog').open || view.polling) return;
  clearTimeout(state.taskTimer);
  view.polling = true;
  renderTaskConversation(view);
  let failed = false;
  try {
    for (const entry of view.entries) {
      if (!entry.taskId || (!refreshAll && settledTaskStatuses.has(entry.status))) continue;
      const detail = await request(`/api/repos/${encodeURIComponent(view.repoId)}/agent/sessions/${encodeURIComponent(view.sessionId)}/tasks/${encodeURIComponent(entry.taskId)}`);
      if (state.conversation !== view || state.authVersion !== view.authVersion) return;
      entry.status = detail.status;
      entry.answer = detail.answer?.content || '';
      entry.terminalReason = detail.terminalReason;
    }
  } catch (error) {
    failed = true;
    if (state.conversation === view) message('task-error', `${error.message} 查询失败不会取消任务；请点击「刷新状态」。`);
  } finally {
    view.polling = false;
    renderTaskConversation(view);
  }
  if (!failed && state.conversation === view && $('task-dialog').open
    && view.entries.some(entry => entry.taskId && !settledTaskStatuses.has(entry.status))) {
    state.taskTimer = setTimeout(() => pollTaskConversation(view), 2500);
  }
}

$('task-form').addEventListener('submit', async event => {
  event.preventDefault();
  const view = state.conversation;
  if (!view || view.submitting || view.storageUnavailable
    || view.entries.some(entry => entry.taskId && !settledTaskStatuses.has(entry.status))) return;
  let entry = view.entries.find(item => !item.taskId);
  if (!entry) {
    const text = $('task-message').value.trim();
    if (!text) return;
    entry = { key: crypto.randomUUID(), message: text };
    view.entries.push(entry);
    if (!saveTaskReferences(view)) { renderTaskConversation(view); return; }
  }
  view.submitting = true;
  message('task-error');
  renderTaskConversation(view);
  try {
    const created = await request(`/api/repos/${encodeURIComponent(view.repoId)}/agent/sessions/${encodeURIComponent(view.sessionId)}/tasks`, {
      method: 'POST', body: { message: entry.message }, key: entry.key
    });
    if (state.authVersion !== view.authVersion) return;
    entry.taskId = created.taskId;
    entry.runId = created.initialRunId;
    // Fetch authoritative lifecycle and answer, even when an idempotent retry returns a completed Task.
    entry.status = undefined;
    saveTaskReferences(view);
    if (state.conversation === view) $('task-message').value = '';
  } catch (error) {
    if (state.conversation === view) message('task-error', `${error.message} 已保留原文和幂等键，可重试原请求。`);
  } finally {
    view.submitting = false;
    renderTaskConversation(view);
  }
  if (entry.taskId) pollTaskConversation(view);
});

$('refresh-task').addEventListener('click', () => {
  message('task-error');
  if (state.conversation) pollTaskConversation(state.conversation, true);
});
$('task-dialog').addEventListener('close', () => { clearTimeout(state.taskTimer); });

if (sessionStorage.getItem('gitnova.token')) showWorkspace();
