<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  uploadDocument,
  createTask,
  getTask,
  getReport,
  getTrace,
  getHistory,
  updateFinding,
  subscribe,
  createSession,
  getMessages,
  sendMessage,
  login,
  register,
  logout as logoutApi,
  setAuth,
  clearAuth,
  getCurrentUser,
  getAllowedTypes,
  onUnauthorized
} from './api'

const STAGES = [
  { key: 'PARSING', label: '解析' },
  { key: 'PLANNING', label: '规划' },
  { key: 'REVIEWING', label: '专家审查' },
  { key: 'CRITIQUING', label: '裁判' },
  { key: 'ARBITRATING', label: '仲裁' },
  { key: 'REPORTING', label: '报告' }
]

const files = ref([])
const batchIndex = ref(0)
const batchTotal = ref(0)
const batchResults = ref([])
const phase = ref('upload')
const busy = ref(false)
const error = ref('')
const task = ref(null)
const report = ref(null)
const trace = ref([])
const showTrace = ref(false)
const activeFindingId = ref(null)
const expandedRunId = ref(null)
const events = ref([])
const session = ref(null)
const chatMessages = ref([])
const chatInput = ref('')
const chatBusy = ref(false)
const chatError = ref('')
const activeCitation = ref(null)
const history = ref([])
const historyError = ref('')
const currentUser = ref(getCurrentUser())
const authMode = ref('login')
const authForm = ref({ username: '', password: '', displayName: '' })
const authError = ref('')
const authBusy = ref(false)
const allowedTypes = ref([])
let source = null

const allowedAccept = computed(() => allowedTypes.value.map((type) => `.${type}`).join(','))
const allowedTypeLabel = computed(() => allowedTypes.value.map((type) => type.toUpperCase()).join(' / '))

onMounted(() => {
  if (currentUser.value) {
    loadHistory()
    loadAllowedTypes()
  }
})

async function loadAllowedTypes() {
  try {
    allowedTypes.value = await getAllowedTypes()
  } catch (e) {
    // 类型清单只影响选择框过滤，失败时不阻塞主流程。
  }
}

// 任意请求返回 401：清空登录态，回到登录页。
onUnauthorized((message) => {
  closeSource()
  clearAuth()
  currentUser.value = null
  authError.value = message || '登录已过期，请重新登录'
})

function closeSource() {
  if (source) {
    source.close()
    source = null
  }
}

function switchAuth(mode) {
  authMode.value = mode
  authError.value = ''
}

async function submitAuth() {
  if (authBusy.value) return
  const username = authForm.value.username.trim()
  const password = authForm.value.password
  if (!username || !password) {
    authError.value = '请输入用户名和密码'
    return
  }
  authBusy.value = true
  authError.value = ''
  try {
    const result = authMode.value === 'login'
      ? await login(username, password)
      : await register(username, password, authForm.value.displayName)
    setAuth(result.user)
    currentUser.value = result.user
    authForm.value = { username: '', password: '', displayName: '' }
    await loadHistory()
    await loadAllowedTypes()
  } catch (e) {
    authError.value = e.message
  } finally {
    authBusy.value = false
  }
}

async function logout() {
  closeSource()
  try {
    await logoutApi()
  } catch (e) {
    // 即使服务端不可达，也要在本地退出。
  }
  clearAuth()
  currentUser.value = null
  authMode.value = 'login'
  authError.value = ''
  allowedTypes.value = []
  resetLocalState()
}

const currentStageIndex = computed(() => {
  const status = task.value ? task.value.status : ''
  const index = STAGES.findIndex((s) => s.key === status)
  if (index >= 0) return index
  return status === 'DONE' || status === 'PARTIAL' ? STAGES.length : -1
})

const activeFinding = computed(() => {
  if (!report.value) return null
  return report.value.findings.find((f) => f.id === activeFindingId.value) || null
})

const expandedRun = computed(() => trace.value.find((r) => r.id === expandedRunId.value) || null)

function onFileChange(event) {
  const selected = event.target.files ? Array.from(event.target.files) : []
  if (!allowedTypes.value.length) {
    files.value = selected
    error.value = ''
    return
  }
  const accepted = selected.filter((file) => allowedTypes.value.includes(extensionOf(file.name)))
  files.value = accepted
  error.value = accepted.length < selected.length
    ? `已忽略不支持的文件，当前仅支持 ${allowedTypeLabel.value}`
    : ''
}

function extensionOf(name) {
  const index = name.lastIndexOf('.')
  return index >= 0 ? name.slice(index + 1).toLowerCase() : ''
}

function removeFile(index) {
  files.value = files.value.filter((_, i) => i !== index)
}

async function start() {
  if (!files.value.length) {
    error.value = `请先选择至少一份 ${allowedTypeLabel.value} 文档`
    return
  }
  busy.value = true
  error.value = ''
  events.value = []
  report.value = null
  trace.value = []
  activeFindingId.value = null
  session.value = null
  chatMessages.value = []
  chatError.value = ''
  activeCitation.value = null
  batchResults.value = []
  batchTotal.value = files.value.length
  const results = []
  try {
    for (let i = 0; i < files.value.length; i += 1) {
      const current = files.value[i]
      batchIndex.value = i + 1
      events.value = []
      task.value = null
      try {
        const document = await uploadDocument(current)
        const created = await createTask(document.id)
        task.value = created
        phase.value = 'running'
        await waitTask(created.id)
        const finalTask = await getTask(created.id)
        task.value = finalTask
        if (finalTask.status === 'FAILED') {
          results.push({ name: current.name, ok: false, message: finalTask.errorMessage || '审查失败' })
        } else {
          results.push({ name: current.name, ok: true, taskId: created.id })
        }
      } catch (e) {
        results.push({ name: current.name, ok: false, message: e.message })
      }
    }
  } finally {
    busy.value = false
    batchResults.value = results
    const success = results.filter((r) => r.ok)
    if (success.length) {
      await loadResult(success[success.length - 1].taskId)
    } else {
      phase.value = 'upload'
      error.value = '全部文件审查失败：' + results.map((r) => `${r.name}（${r.message}）`).join('；')
    }
    loadHistory()
  }
}

function waitTask(taskId) {
  return new Promise((resolve) => {
    closeSource()
    source = subscribe(taskId, (event) => {
      if (task.value) {
        task.value = {
          ...task.value,
          status: event.stage || task.value.status,
          progress: event.progress,
          stageMessage: event.message
        }
      }
      events.value.push(event)
      if (event.stage === 'FAILED' || event.stage === 'DONE' || event.stage === 'PARTIAL' || event.progress >= 100) {
        closeSource()
        resolve()
      }
    })
  })
}

async function loadResult(taskId) {
  const finalTask = await getTask(taskId)
  task.value = finalTask
  if (finalTask.status === 'FAILED') {
    error.value = finalTask.errorMessage || '审查失败'
    return false
  }
  report.value = await getReport(taskId)
  trace.value = await getTrace(taskId)
  phase.value = 'result'
  initChat()
  return true
}

async function resumeTask(taskId) {
  phase.value = 'running'
  try {
    await waitTask(taskId)
    await loadResult(taskId)
  } catch (e) {
    error.value = e.message
    phase.value = 'upload'
  }
}

async function initChat() {
  if (!report.value) return
  chatError.value = ''
  try {
    session.value = await createSession(report.value.task.documentId, report.value.task.id)
    chatMessages.value = await getMessages(session.value.id)
  } catch (e) {
    chatError.value = '问答会话创建失败：' + e.message
  }
}

async function scrollChat() {
  await nextTick()
  const el = document.getElementById('chat-messages')
  if (el) el.scrollTop = el.scrollHeight
}

async function sendChat() {
  const content = chatInput.value.trim()
  if (!content || !session.value || chatBusy.value) return

  chatBusy.value = true
  chatError.value = ''
  chatInput.value = ''
  const user = { id: 'local-user', role: 'user', content, citations: [], status: 'DONE' }
  const assistant = {
    id: 'streaming', role: 'assistant', content: '', citations: [], toolCalls: [], status: 'STREAMING'
  }
  chatMessages.value.push(user, assistant)
  scrollChat()

  try {
    await sendMessage(session.value.id, content, ({ type, data }) => {
      if (type === 'start') {
        user.id = data.userMessageId
        assistant.id = data.messageId
      } else if (type === 'token') {
        assistant.content += data.delta || ''
        scrollChat()
      } else if (type === 'tool_call') {
        assistant.toolCalls.push({ tool: data.tool, args: data.args || {} })
      } else if (type === 'done') {
        assistant.id = data.messageId
        assistant.citations = data.citations || []
        assistant.status = 'DONE'
      } else if (type === 'error') {
        assistant.status = 'FAILED'
        chatError.value = data.message || '问答失败'
      }
    })
    if (assistant.status === 'STREAMING') assistant.status = 'DONE'
  } catch (e) {
    assistant.status = 'FAILED'
    chatError.value = e.message
  } finally {
    if (!assistant.content && assistant.status === 'FAILED') {
      assistant.content = '本次回答失败，请重试。'
    }
    chatBusy.value = false
    scrollChat()
  }
}

async function selectCitation(citation) {
  activeFindingId.value = null
  activeCitation.value = citation
  await nextTick()
  const el = document.getElementById('block-' + citation.blockId)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function levelText(level) {
  return { HIGH: '高危', MEDIUM: '中危', LOW: '低危' }[level] || level
}

function levelClass(level) {
  return (level || '').toLowerCase()
}

async function selectFinding(finding) {
  activeFindingId.value = finding.id
  activeCitation.value = null
  await nextTick()
  const el = document.getElementById('block-' + finding.blockId)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

async function review(finding, status) {
  try {
    const updated = await updateFinding(finding.id, status)
    finding.humanStatus = updated.humanStatus
  } catch (e) {
    error.value = e.message
  }
}

function segments(block) {
  const finding = activeFinding.value
  const citation = activeCitation.value
  const quote = citation && citation.blockId === block.id
    ? citation.quote
    : finding && finding.blockId === block.id ? finding.quote : null
  if (!quote) {
    return [{ text: block.text, mark: false }]
  }
  const index = block.text.indexOf(quote)
  if (index < 0) {
    return [{ text: block.text, mark: false }]
  }
  return [
    { text: block.text.slice(0, index), mark: false },
    { text: quote, mark: true },
    { text: block.text.slice(index + quote.length), mark: false }
  ]
}

function reset() {
  resetLocalState()
  loadHistory()
}

/** 只清页面状态，不发起需要登录的请求（退出登录时复用）。 */
function resetLocalState() {
  closeSource()
  files.value = []
  batchResults.value = []
  batchIndex.value = 0
  batchTotal.value = 0
  phase.value = 'upload'
  task.value = null
  report.value = null
  trace.value = []
  events.value = []
  error.value = ''
  activeFindingId.value = null
  expandedRunId.value = null
  showTrace.value = false
  session.value = null
  chatMessages.value = []
  chatInput.value = ''
  chatBusy.value = false
  chatError.value = ''
  activeCitation.value = null
  history.value = []
  historyError.value = ''
}

async function loadHistory() {
  historyError.value = ''
  try {
    history.value = await getHistory()
  } catch (e) {
    historyError.value = '历史记录加载失败：' + e.message
  }
}

async function openHistory(item) {
  if (item.status === 'FAILED' || item.status === 'CREATED') {
    historyError.value = item.status === 'FAILED' ? '该记录审查失败，无法查看结果' : '该记录尚未开始审查'
    return
  }
  closeSource()
  historyError.value = ''
  error.value = ''
  busy.value = true
  report.value = null
  trace.value = []
  events.value = []
  activeFindingId.value = null
  expandedRunId.value = null
  showTrace.value = false
  session.value = null
  chatMessages.value = []
  chatInput.value = ''
  chatError.value = ''
  activeCitation.value = null
  try {
    task.value = await getTask(item.taskId)
    if (task.value.status === 'FAILED') {
      error.value = task.value.errorMessage || '该记录审查失败'
      phase.value = 'upload'
      return
    }
    if (task.value.status !== 'DONE' && task.value.status !== 'PARTIAL') {
      busy.value = false
      resumeTask(item.taskId)
      return
    }
    report.value = await getReport(item.taskId)
    trace.value = await getTrace(item.taskId)
    phase.value = 'result'
    await initChat()
    loadHistory()
  } catch (e) {
    error.value = e.message
    phase.value = 'upload'
  } finally {
    busy.value = false
  }
}

function statusText(status) {
  return {
    CREATED: '待审查',
    PARSING: '解析中',
    PLANNING: '规划中',
    REVIEWING: '审查中',
    VALIDATING: '校验中',
    ARBITRATING: '仲裁中',
    REPORTING: '生成报告',
    DONE: '已完成',
    PARTIAL: '部分完成',
    FAILED: '失败'
  }[status] || status
}
</script>

<template>
  <div class="page">
    <header>
      <div>
        <div class="brand">智能文档审查<span class="tag">Multi-Agent</span></div>
        <div class="sub">解析 → 规划 → 专家并行审查 → 裁判校验 → 仲裁定级 → 带原文定位的报告</div>
      </div>
      <div v-if="currentUser" class="user-bar">
        <span class="user-name">{{ currentUser.displayName || currentUser.username }}</span>
        <button class="link-btn" @click="logout">退出登录</button>
      </div>
    </header>

    <section v-if="!currentUser" class="card auth-card">
      <div class="auth-tabs">
        <button :class="{ active: authMode === 'login' }" @click="switchAuth('login')">登录</button>
        <button :class="{ active: authMode === 'register' }" @click="switchAuth('register')">注册</button>
      </div>
      <div class="auth-form">
        <label>
          <span>用户名</span>
          <input v-model="authForm.username" placeholder="3~32 个字符" autocomplete="username" />
        </label>
        <label v-if="authMode === 'register'">
          <span>昵称（可选）</span>
          <input v-model="authForm.displayName" placeholder="展示名称" />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="authForm.password"
            type="password"
            :placeholder="authMode === 'login' ? '请输入密码' : '至少 8 位'"
            :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'"
            @keydown.enter.prevent="submitAuth"
          />
        </label>
        <button class="primary auth-submit" :disabled="authBusy" @click="submitAuth">
          {{ authBusy ? '处理中…' : authMode === 'login' ? '登录' : '注册并登录' }}
        </button>
        <div v-if="authError" class="error">{{ authError }}</div>
        <div class="hint">登录后只能看到自己的审查历史与问答会话；推理由 Python LangGraph 服务执行。</div>
      </div>
    </section>

    <template v-else>

    <section v-if="phase === 'upload'" class="card">
      <div class="upload-row">
        <input class="file-input" type="file" :accept="allowedAccept" multiple @change="onFileChange" />
        <button class="primary" :disabled="busy || !files.length" @click="start">
          {{ busy ? `审查中 ${batchIndex}/${batchTotal}…` : files.length > 1 ? `开始审查（${files.length} 个文件）` : '开始审查' }}
        </button>
      </div>
      <div v-if="files.length" class="file-list">
        <div v-for="(item, index) in files" :key="`${item.name}-${index}`" class="file-item">
          <span class="file-name">{{ item.name }}</span>
          <span class="file-size">{{ (item.size / 1024).toFixed(1) }} KB</span>
          <button class="link-btn" :disabled="busy" @click="removeFile(index)">移除</button>
        </div>
      </div>
      <div class="hint">
        支持一次选择多个 {{ allowedTypeLabel }} 文件，按顺序逐个审查，每个文件生成一条独立记录。推理由 Python LangGraph 服务执行，默认使用离线规则；真实 DeepSeek 模型通过 agent-service 环境变量启用。
      </div>
      <div v-if="error" class="error">{{ error }}</div>
    </section>

    <section v-if="phase === 'upload' && history.length" class="card">
      <div class="pane-title history-head">
        <span>历史审查记录</span>
        <span class="hint-inline">点击可回到该文档继续对话</span>
      </div>
      <div class="history-list">
        <button
          v-for="item in history"
          :key="item.taskId"
          class="history-item"
          :class="{ muted: item.status === 'FAILED' || item.status === 'CREATED' }"
          @click="openHistory(item)"
        >
          <div class="history-main">
            <strong>{{ item.documentName }}</strong>
            <span class="badge ghost">{{ statusText(item.status) }}</span>
            <span v-if="item.docType" class="badge ghost">{{ item.docType }}</span>
          </div>
          <div class="history-meta">
            <span>
              风险 {{ item.findingCount }} · 高危 {{ item.highCount }} · 中危 {{ item.mediumCount }} · 低危 {{ item.lowCount }}
            </span>
            <span>{{ item.createdAt }}</span>
          </div>
        </button>
      </div>
      <div v-if="historyError" class="error">{{ historyError }}</div>
    </section>

    <section v-if="phase === 'running'" class="card">
      <div class="steps">
        <div
          v-for="(stage, index) in STAGES"
          :key="stage.key"
          class="step"
          :class="{ active: index === currentStageIndex, done: index < currentStageIndex }"
        >
          {{ index + 1 }}. {{ stage.label }}
        </div>
      </div>
      <div v-if="batchTotal > 1" class="batch-line">
        正在审查 {{ batchIndex }} / {{ batchTotal }}：{{ files[batchIndex - 1] ? files[batchIndex - 1].name : '' }}
      </div>
      <div class="bar">
        <i :style="{ width: (task ? task.progress : 0) + '%' }"></i>
      </div>
      <div class="progress-text">
        {{ task ? task.stageMessage : '准备中' }}
        <span v-if="task && task.docType">· 文档类型：{{ task.docType }}</span>
        <span> · {{ task ? task.progress : 0 }}%</span>
      </div>

      <div class="trace-list">
        <div v-for="(event, index) in events" :key="index" class="trace-item" style="cursor: default">
          <div class="row">
            <span class="badge ghost">{{ event.progress }}%</span>
            <span>{{ event.message }}</span>
          </div>
        </div>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
      <div style="margin-top: 14px">
        <button @click="reset">重新开始</button>
      </div>
    </section>

    <section v-if="phase === 'result' && report" class="card">
      <div v-if="batchResults.length > 1" class="hint batch-summary">
        本批次共 {{ batchResults.length }} 个文件：成功 {{ batchResults.filter((r) => r.ok).length }}，
        失败 {{ batchResults.filter((r) => !r.ok).length }}。当前展示最后一个成功的文件，其余可在历史记录中查看。
        <span v-for="item in batchResults.filter((r) => !r.ok)" :key="item.name" class="batch-fail">
          {{ item.name }}：{{ item.message }}
        </span>
      </div>
      <div class="summary-grid">
        <div class="summary-item">
          <b>{{ report.findings.length }}</b>
          <span>风险总数</span>
        </div>
        <div class="summary-item high">
          <b>{{ report.highCount }}</b>
          <span>高危</span>
        </div>
        <div class="summary-item medium">
          <b>{{ report.mediumCount }}</b>
          <span>中危</span>
        </div>
        <div class="summary-item low">
          <b>{{ report.lowCount }}</b>
          <span>低危</span>
        </div>
      </div>

      <div class="summary-text">{{ report.summary }}</div>

      <div class="split">
        <div class="pane">
          <div class="pane-title">
            <span>原文</span>
            <span>{{ report.blocks.length }} 个片段</span>
          </div>
          <div class="blocks">
            <div
              v-for="block in report.blocks"
              :id="'block-' + block.id"
              :key="block.id"
              class="block"
              :class="{
                heading: block.type === 'HEADING',
                active: (activeFinding && activeFinding.blockId === block.id) ||
                  (activeCitation && activeCitation.blockId === block.id)
              }"
            >
              <span class="block-meta">{{ block.page > 0 ? 'P' + block.page : '' }}</span>
              <template v-for="(segment, index) in segments(block)" :key="index">
                <mark v-if="segment.mark">{{ segment.text }}</mark>
                <span v-else>{{ segment.text }}</span>
              </template>
            </div>
          </div>
        </div>

        <div class="pane">
          <div class="pane-title">
            <span>风险清单</span>
            <span>点击定位原文</span>
          </div>
          <div class="findings">
            <div
              v-for="finding in report.findings"
              :key="finding.id"
              class="finding"
              :class="{ active: activeFindingId === finding.id }"
              @click="selectFinding(finding)"
            >
              <div class="finding-head">
                <span class="badge" :class="levelClass(finding.level)">{{ levelText(finding.level) }}</span>
                <strong>{{ finding.ruleId }}</strong>
                <span v-if="finding.conflict" class="badge ghost">存在分歧</span>
                <span v-if="finding.humanStatus === 'CONFIRMED'" class="badge low">已确认</span>
                <span v-if="finding.humanStatus === 'REJECTED'" class="badge ghost">已驳回</span>
                <span style="margin-left: auto; color: #a0a7b4; font-size: 11px">
                  {{ finding.agentName }} · {{ finding.page > 0 ? '第 ' + finding.page + ' 页' : '位置未知' }}
                </span>
              </div>
              <div class="quote">“{{ finding.quote }}”</div>
              <div class="reason"><b>理由：</b>{{ finding.reason }}</div>
              <div class="suggestion"><b>建议：</b>{{ finding.suggestion }}</div>
              <div class="finding-actions" @click.stop>
                <button @click="review(finding, 'CONFIRMED')">确认风险</button>
                <button @click="review(finding, 'REJECTED')">驳回</button>
              </div>
            </div>
            <div v-if="report.findings.length === 0" class="hint">未发现命中审查清单的风险条款。</div>
          </div>
        </div>
      </div>

      <section class="chat-panel">
        <div class="pane-title">
          <span>单文档多轮问答</span>
          <span>Python LangGraph · 引用可定位原文</span>
        </div>
        <div id="chat-messages" class="chat-messages">
          <div v-if="!session && !chatError" class="hint">正在创建问答会话…</div>
          <div v-else-if="session && chatMessages.length === 0" class="chat-empty">
            可以追问“违约金条款有什么风险”或“总结这份文档的高危问题”。
          </div>
          <div
            v-for="message in chatMessages"
            :key="message.id"
            class="chat-message"
            :class="message.role"
          >
            <div class="message-body">
              <div class="message-role">{{ message.role === 'user' ? '你' : '文档助手' }}</div>
              <div class="message-content">{{ message.content || (message.status === 'STREAMING' ? '正在检索与回答…' : '') }}</div>
              <div v-if="message.toolCalls && message.toolCalls.length" class="tool-calls">
                <span v-for="(call, index) in message.toolCalls" :key="index" class="badge ghost">
                  {{ call.tool }}<template v-if="call.args.q"> · {{ call.args.q }}</template>
                </span>
              </div>
              <div v-if="message.citations && message.citations.length" class="citations">
                <button
                  v-for="(citation, index) in message.citations"
                  :key="citation.blockId + index"
                  class="citation"
                  @click="selectCitation(citation)"
                >
                  原文 {{ index + 1 }}：{{ citation.quote }}
                </button>
              </div>
            </div>
          </div>
        </div>
        <div v-if="chatError" class="error">{{ chatError }}</div>
        <div class="chat-composer">
          <textarea
            v-model="chatInput"
            rows="2"
            placeholder="基于当前文档追问，Enter 发送，Shift+Enter 换行"
            :disabled="!session || chatBusy"
            @keydown.enter.exact.prevent="sendChat"
          ></textarea>
          <button class="primary" :disabled="!session || chatBusy || !chatInput.trim()" @click="sendChat">
            {{ chatBusy ? '回答中…' : '发送' }}
          </button>
        </div>
      </section>

      <div class="trace-toggle">
        <button @click="showTrace = !showTrace">{{ showTrace ? '收起执行轨迹' : '查看执行轨迹' }}</button>
        <span class="hint" style="margin: 0">
          每个 Agent 的输入输出均可回放，共 {{ trace.length }} 次调用
        </span>
      </div>

      <div v-if="showTrace" class="trace-list">
        <div
          v-for="run in trace"
          :key="run.id"
          class="trace-item"
          @click="expandedRunId = expandedRunId === run.id ? null : run.id"
        >
          <div class="row">
            <span class="badge" :class="run.status === 'SUCCESS' ? 'low' : 'high'">{{ run.status }}</span>
            <strong>{{ run.agentName }}</strong>
            <span style="color: #a0a7b4">{{ run.latencyMs }} ms</span>
            <span style="color: #a0a7b4">≈{{ run.tokens }} tokens</span>
          </div>
          <div v-if="expandedRunId === run.id">
            <pre>【输入】\n{{ run.input }}</pre>
            <pre>【输出】\n{{ run.output }}</pre>
            <pre v-if="run.errorMessage">【错误】\n{{ run.errorMessage }}</pre>
          </div>
        </div>
      </div>

      <div class="trace-toggle">
        <button @click="reset">审查另一份文档</button>
      </div>
    </section>

    </template>
  </div>
</template>
