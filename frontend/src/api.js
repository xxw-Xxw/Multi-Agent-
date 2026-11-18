const BASE = '/api'
const USER_KEY = 'docreview.user'

let unauthorizedHandler = null

/** 401 时由 App.vue 注册回调，统一清理登录态并回到登录页。 */
export function onUnauthorized(handler) {
  unauthorizedHandler = handler
}

/** 仅缓存展示用的用户信息；token 由服务端 HttpOnly Cookie 持有，脚本无法读取。 */
export function getCurrentUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (e) {
    return null
  }
}

export function setAuth(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAuth() {
  localStorage.removeItem(USER_KEY)
}

async function ensureOk(response) {
  if (response.ok) return response
  let message = `请求失败 (${response.status})`
  try {
    const body = await response.json()
    if (body && body.message) message = body.message
  } catch (e) {
    // 非 JSON 错误响应
  }
  if (response.status === 401) {
    clearAuth()
    if (unauthorizedHandler) unauthorizedHandler(message)
  }
  throw new Error(message)
}

async function request(url, options = {}) {
  const response = await ensureOk(await fetch(BASE + url, {
    ...options,
    credentials: 'include'
  }))
  return response.json()
}

export function login(username, password) {
  return request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
}

export function register(username, password, displayName) {
  return request('/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, displayName })
  })
}

/** 服务端吊销 token 并清除 Cookie。 */
export function logout() {
  return request('/auth/logout', { method: 'POST' })
}

/** 可选文件类型由后端返回，前端不再维护第二份清单。 */
export function getAllowedTypes() {
  return request('/documents/allowed-types')
}

export function uploadDocument(file) {
  const form = new FormData()
  form.append('file', file)
  return request('/documents', { method: 'POST', body: form })
}

export function createTask(documentId) {
  return request('/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ documentId })
  })
}

export function getTask(taskId) {
  return request(`/tasks/${taskId}`)
}

export function getHistory() {
  return request('/history')
}

export function getReport(taskId) {
  return request(`/tasks/${taskId}/report`)
}

export function getTrace(taskId) {
  return request(`/tasks/${taskId}/trace`)
}

export function updateFinding(id, humanStatus) {
  return request(`/findings/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ humanStatus })
  })
}

/** EventSource 同源请求会自动携带 HttpOnly Cookie，无需在 URL 里传 token。 */
export function subscribe(taskId, onEvent) {
  const source = new EventSource(`${BASE}/tasks/${taskId}/stream`, { withCredentials: true })
  source.addEventListener('progress', (event) => {
    onEvent(JSON.parse(event.data))
  })
  return source
}

export function createSession(documentId, taskId) {
  return request('/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ documentId, taskId })
  })
}

export function getMessages(sessionId) {
  return request(`/sessions/${sessionId}/messages`)
}

/** POST + ReadableStream 解析 SSE，处理跨 chunk 的事件边界。 */
export async function sendMessage(sessionId, message, onEvent) {
  const response = await ensureOk(await fetch(`${BASE}/sessions/${sessionId}/messages`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message })
  }))
  if (!response.body) throw new Error('浏览器不支持流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (block) => {
    let type = 'message'
    const data = []
    for (const line of block.split(/\r\n|\n|\r/)) {
      if (line.startsWith('event:')) type = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (data.length > 0) onEvent({ type, data: JSON.parse(data.join('\n')) })
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    let separator = buffer.match(/\r\n\r\n|\n\n|\r\r/)
    while (separator) {
      dispatch(buffer.slice(0, separator.index))
      buffer = buffer.slice(separator.index + separator[0].length)
      separator = buffer.match(/\r\n\r\n|\n\n|\r\r/)
    }
    if (done) break
  }
  if (buffer.trim()) dispatch(buffer)
}
