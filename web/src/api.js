const TOKEN_KEY = 'mengx_token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function saveToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** 默认记住密码：登录成功后保存，下次进登录页自动回填（信任圈产品，明文存本机） */
const CREDENTIALS_KEY = 'mengx_credentials';

export function saveCredentials(phone, password) {
  localStorage.setItem(CREDENTIALS_KEY, JSON.stringify({ phone, password }));
}

export function loadCredentials() {
  try {
    return JSON.parse(localStorage.getItem(CREDENTIALS_KEY)) || null;
  } catch {
    return null;
  }
}

/** 统一 API 调用：自动携带 token，非 2xx 抛出带后端 message 的 Error；signal 可选（用于请求超时中止） */
export async function api(path, { method = 'GET', body, signal } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const resp = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    throw new Error(data.message || `请求失败（${resp.status}）`);
  }
  return data;
}

/** 解析 SSE 流：事件以空行分隔，多行 data: 按规范拼接 */
async function consumeSse(resp, onEvent) {
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const parts = buf.split('\n\n');
    buf = parts.pop();
    for (const part of parts) {
      const dataLines = part
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.replace(/^data:? ?/, ''));
      if (!dataLines.length) continue;
      onEvent(JSON.parse(dataLines.join('\n')));
    }
  }
}

/**
 * 百晓通流式访谈：POST /api/idea/chat/stream（JWT）
 * mode：host=仙官本色 / law=法学助手（消息级路由）
 * greet：true=法学助手出场触发（无用户消息，由后端注入触发指令，开场白不落 user 行）
 * clientRoundId：一轮对话的幂等键，同键重发后端只落库一次（greet 出场不传）
 * 事件 {type, content}：thinking 掐指一算中 | chunk 增量文本 | card 功能卡 DTO JSON | done 结束 | error 失败
 *   | design 营造图 JSON（content 为 JSON 字符串：focus 本轮聚焦 / question 建议问题 / options 建议选项 /
 *     proposal 建议方案 / validity 合理性判定 / decisions 待表态项 / closing / closingConfirmed / ack /
 *     priority / snapshot{core{functions,effects,approach}, dimensions[]} / keyItems[]；
 *     历史恢复走 GET /api/idea/sessions/{id}/design，返回 {design:"<同样的 JSON 字符串>"}）
 * 注意：宣布定稿后卡片经旁路异步装配，card 事件可能晚于 done 到达（流已关则静默丢弃），
 * 前端由 Chat 页的 notices 轮询按 id 去重补收到场
 */
export async function streamIdeaChat(sessionId, messages, mode, onEvent, greet = false, clientRoundId = null) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const resp = await fetch('/api/idea/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ sessionId, messages, mode, greet, clientRoundId }),
  });
  if (!resp.ok) {
    const data = await resp.json().catch(() => ({}));
    throw new Error(data.message || `请求失败（${resp.status}）`);
  }
  await consumeSse(resp, onEvent);
}

/**
 * 造物线聊天（回路 B，与主回路物理隔离）：POST /api/forge/jobs/{jobId}/chat/stream。
 * content 消息文本；questionId 为点选项按钮携带的挂起问题标记（零歧义直达答题）。
 * 事件 {type, content}：chunk 分身回复增量 | done 分身确认关流（后台 resume，新问题轮询可见）| error 失败
 */
export async function streamForgeChat(jobId, content, questionId, onEvent) {
  const headers = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const resp = await fetch(`/api/forge/jobs/${jobId}/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ content, questionId }),
  });
  if (!resp.ok) {
    const data = await resp.json().catch(() => ({}));
    throw new Error(data.message || `请求失败（${resp.status}）`);
  }
  await consumeSse(resp, onEvent);
}

/**
 * 造物线终审三动作：approve 批准出稿（→排版官→DONE）/ reject 打回指定岗重造（必带 target+reason）
 * / cancel 搁置谢绝（→CANCELLED）。REVIEW 态专用，全员可操作。
 */
export function reviewForgeJob(jobId, body) {
  return api(`/api/forge/jobs/${jobId}/review`, { method: 'POST', body });
}

/** 造物线断点重试：FAILED/BLOCKED → RUNNING，从当前岗继续推进 */
export function retryForgeJob(jobId) {
  return api(`/api/forge/jobs/${jobId}/retry`, { method: 'POST' });
}
