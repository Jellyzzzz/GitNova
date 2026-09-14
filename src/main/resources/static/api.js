// Same-origin API adapter. Never send credentials to a URL supplied by repository data.
export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.status = status; }
}

export async function request(path, { method = 'GET', body, form, key, timeout = 20000 } = {}) {
  if (!path.startsWith('/api/')) throw new Error('Only same-origin API paths are allowed');
  const token = sessionStorage.getItem('gitnova.token');
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  if (form) { body = new URLSearchParams(form); }
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(path, { method, headers, body, signal: controller.signal, redirect: 'error' });
    const result = await response.json().catch(() => null);
    const code = response.ok ? result?.code : response.status;
    if (code !== 200) {
      if (code === 401 && token && token === sessionStorage.getItem('gitnova.token')) {
        window.dispatchEvent(new Event('gitnova:unauthorized'));
      }
      let message = result?.message || '请求失败，请检查服务是否正常启动。';
      if (code === 429) {
        const retry = response.headers.get('Retry-After');
        message = `请求过于频繁，请${retry && /^\d+$/.test(retry) ? `在 ${retry} 秒后` : '稍后'}重试。`;
      }
      throw new ApiError(message, code || response.status);
    }
    return result.data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(error.name === 'AbortError'
      ? '请求超时。操作可能已在服务端生效，请先刷新查看；创建 Session 可使用原请求重试。'
      : '无法连接服务。操作是否生效尚不确定，请检查网络并刷新查看。');
  } finally { clearTimeout(timer); }
}
