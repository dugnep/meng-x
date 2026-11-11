import { useEffect, useRef, useState } from 'react';
import { api, streamForgeChat, reviewForgeJob, retryForgeJob, getToken } from '../api';

/** 岗链四阶段（与后端 CHAIN 对齐；advisor 为插拔槽位，展示统一叫「领域顾问」） */
const STAGES = [
  { key: 'analyst', label: '需求分析' },
  { key: 'advisor', label: '领域顾问' },
  { key: 'architect', label: '架构师' },
  { key: 'reviewer', label: '审校官' },
];

/** 工单状态 → 中文标签 + 徽章样式类 */
const STATUS_META = {
  RUNNING: { label: '施工中', cls: 'st-running' },
  WAITING_USER: { label: '等定夺', cls: 'st-waiting' },
  BLOCKED: { label: '遇阻', cls: 'st-blocked' },
  FAILED: { label: '出岔子', cls: 'st-failed' },
  REVIEW: { label: '待终审', cls: 'st-review' },
  DONE: { label: '已出稿', cls: 'st-done' },
  CANCELLED: { label: '已取消', cls: 'st-cancelled' },
};

/** 分身消息信封（idea_messages.content 的 JSON）→ 渲染模型；解析失败降级纯文本 */
function parseEnvelope(content) {
  try {
    const env = JSON.parse(content);
    if (env && env.type && env.rendered) {
      return { type: env.type, rendered: env.rendered, questionId: env.questionId, options: env.options || [] };
    }
  } catch {
    // 旧版/异常消息：按纯文本渲染
  }
  return { type: 'text', rendered: content, questionId: null, options: [] };
}

/** stage 槽位匹配（advisor 可能带具体选型后缀，归一化前缀比较） */
function stageMatches(stage, key) {
  return stage === key || (stage || '').startsWith(key + '.');
}

/**
 * 造物线会话页（回路 B，与主线聊天物理隔离）：与仙官分身一问一答推进工单。
 * 消息下发为「后台只写库不推、前端轮询」：分身确认即关流，新挂起问题经 12s
 * 轮询可见；挂起问题渲染成选项按钮，点选携带 questionId 零歧义直达答题。
 */
export default function ForgeChat({ jobId, onBack, onLogout }) {
  const [job, setJob] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [reply, setReply] = useState(null); // 发送期间的分身临时气泡（null=无）
  const [error, setError] = useState('');
  const [acting, setActing] = useState(false); // 终审/重试动作执行中
  const [showReject, setShowReject] = useState(false); // 打回表单展开
  const [rejectTarget, setRejectTarget] = useState('analyst');
  const [rejectReason, setRejectReason] = useState('');
  const listRef = useRef(null);
  const inputRef = useRef(null);
  const sendingRef = useRef(false); // 轮询与发送并发时，轮询跳过刷新避免重复气泡

  /** 拉工单详情 + 消息流 */
  async function loadAll() {
    const [j, msgs] = await Promise.all([
      api(`/api/forge/jobs/${jobId}`),
      api(`/api/forge/jobs/${jobId}/messages`),
    ]);
    setJob(j);
    setMessages(msgs);
  }

  // 进页加载 + 12s 轮询（页面隐藏跳过；发送中跳过——本地临时气泡与后端落库会重复）
  useEffect(() => {
    let stopped = false;
    const tick = () => {
      if (stopped || document.hidden || sendingRef.current) return;
      loadAll().catch((e) => setError(e.message));
    };
    tick();
    const timer = setInterval(tick, 12000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId]);

  // 滚动到底
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages, reply]);

  // 当前挂起问题（一岗一轮只挂一个）
  const pendingQ =
    job?.status === 'WAITING_USER' && job?.pendingQuestions?.length ? job.pendingQuestions[0] : null;
  const meta = job ? STATUS_META[job.status] || { label: job.status, cls: '' } : null;

  /** 阶段进度：REVIEW/DONE 全部完成；BLOCKED/FAILED 当前岗标红；其余按岗链索引推进；
   *  stage=formatter（排版中）不在四岗链内，视为四岗全部完成 */
  function stageState(idx) {
    const st = job?.status;
    if (st === 'REVIEW' || st === 'DONE') return 'done';
    const curIdx = STAGES.findIndex((s) => stageMatches(job?.stage, s.key));
    if ((st === 'BLOCKED' || st === 'FAILED') && idx === curIdx) return 'bad';
    if (curIdx < 0) return 'done';
    if (idx < curIdx) return 'done';
    if (idx === curIdx) return 'now';
    return 'todo';
  }

  /** 发消息 / 点选项（选项携带 questionId 直达答题，绕过三分类） */
  async function send(text, questionId) {
    if (!text || sending) return;
    setInput('');
    setSending(true);
    sendingRef.current = true;
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    let acc = '';
    setReply('');
    try {
      await streamForgeChat(jobId, text, questionId, (ev) => {
        if (ev.type === 'chunk') {
          acc += ev.content;
          setReply(acc);
        } else if (ev.type === 'error') {
          acc += `\n⚠️ ${ev.content || '出错了'}`;
          setReply(acc);
        }
      });
    } catch (err) {
      acc += `\n⚠️ ${err.message}`;
      setReply(acc);
    } finally {
      setSending(false);
      sendingRef.current = false;
      // 先拉最新落库（分身回复/新挂起问题/状态流转），再撤临时气泡避免空窗
      loadAll()
        .catch((e) => setError(e.message))
        .finally(() => setReply(null));
    }
  }

  async function handleSend(e) {
    e.preventDefault();
    await send(input.trim(), undefined);
  }

  /** 终审动作（§5.5 主理人介入：结构化操作不进对话）：approve/reject/cancel */
  async function act(action, target, reason) {
    if (acting) return;
    if (action === 'cancel' && !window.confirm('搁置后这个应用的施工将终止，确定？')) return;
    setActing(true);
    try {
      await reviewForgeJob(jobId, { action, target, reason });
      setShowReject(false);
      setRejectReason('');
      await loadAll();
    } catch (err) {
      alert(err.message);
    } finally {
      setActing(false);
    }
  }

  /** 断点重试：FAILED/BLOCKED 从当前岗继续 */
  async function retryNow() {
    if (acting) return;
    setActing(true);
    try {
      await retryForgeJob(jobId);
      await loadAll();
    } catch (err) {
      alert(err.message);
    } finally {
      setActing(false);
    }
  }

  /** 下载施工包（DONE 后）：GET /api/idea/cards/{cardId}/build，Markdown 文件 */
  async function downloadPackage() {
    const resp = await fetch(`/api/idea/cards/${job.cardId}/build`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    });
    if (!resp.ok) {
      alert('施工包还没出稿或已失效，请稍后再试');
      return;
    }
    const blob = await resp.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `施工文档-${job.cardTitle || '应用'}.md`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  /** 分身气泡：信封正文 + 选项按钮（挂起问题仍待答时才可点） */
  function renderAssistant(m, i) {
    const env = parseEnvelope(m.content);
    const answerable = !sending && pendingQ && pendingQ.id === env.questionId;
    return (
      <div key={i} className="bubble assistant">
        <span className="forge-tag">点石 · 开发线</span>
        {env.rendered}
        {env.type === 'question' && env.options.length > 0 && (
          <div className="forge-options">
            {env.options.map((op) => (
              <button
                key={op}
                type="button"
                className="forge-option-btn"
                disabled={!answerable}
                onClick={() => send(op, env.questionId)}
              >
                {op}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="chat-page forge-page">
      <header className="forge-header">
        <button type="button" className="icon-btn" title="回首页聊" aria-label="回首页聊" onClick={onBack}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M3 10.5L12 3l9 7.5" />
            <path d="M5 9.5V21h5v-6h4v6h5V9.5" />
          </svg>
        </button>
        <span className="forge-title">{job?.cardTitle || '开发线'}</span>
        {meta && <span className={`forge-badge ${meta.cls}`}>{meta.label}</span>}
        {job && <span className="forge-ver">设计稿 v{job.designVersion || 1}</span>}
        <button className="logout-btn" onClick={onLogout}>
          退出
        </button>
      </header>

      <div className="forge-stage-bar">
        {STAGES.map((s, i) => (
          <div key={s.key} className={`forge-stage ${stageState(i)}`}>
            <span>{s.label}</span>
          </div>
        ))}
      </div>
      {job?.errorNote && (
        <p className="forge-note" title={job.errorNote}>
          ⚠ {job.errorNote}
        </p>
      )}

      {job?.status === 'REVIEW' && (
        <div className="forge-review-panel">
          <p className="forge-review-title">
            终审：读过上面的分身禀报后定夺——批准即出施工包，打回需指定岗位与原因
          </p>
          <div className="forge-review-actions">
            <button type="button" className="fr-approve-btn" disabled={acting} onClick={() => act('approve')}>
              批准出稿
            </button>
            <button
              type="button"
              className="fr-reject-btn"
              disabled={acting}
              onClick={() => setShowReject((v) => !v)}
            >
              打回重造
            </button>
            <button type="button" className="fr-cancel-btn" disabled={acting} onClick={() => act('cancel')}>
              搁置谢绝
            </button>
          </div>
          {showReject && (
            <div className="forge-reject-form">
              <select value={rejectTarget} onChange={(e) => setRejectTarget(e.target.value)}>
                {STAGES.map((s) => (
                  <option key={s.key} value={s.key}>
                    打回给{s.label}
                  </option>
                ))}
              </select>
              <textarea
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                maxLength={300}
                placeholder="打回原因（必填，工部照此重造）…"
              />
              <button
                type="button"
                className="fr-reject-submit"
                disabled={acting || !rejectReason.trim()}
                onClick={() => act('reject', rejectTarget, rejectReason.trim())}
              >
                确认打回
              </button>
            </div>
          )}
        </div>
      )}

      {job?.status === 'DONE' && (
        <div className="forge-op-row">
          <button type="button" className="fr-approve-btn" onClick={downloadPackage}>
            下载施工包
          </button>
        </div>
      )}

      {(job?.status === 'BLOCKED' || job?.status === 'FAILED') && (
        <div className="forge-op-row">
          <button type="button" className="fr-retry-btn" disabled={acting} onClick={retryNow}>
            从断点重试
          </button>
        </div>
      )}

      <main className="chat-list" ref={listRef}>
        <div className="chat-col">
          {error && <p className="board-error">{error}</p>}
          {messages.length === 0 && reply === null && (
            <p className="chat-empty">工部正在准备，分身马上就来…</p>
          )}
          {messages.map((m, i) =>
            m.role === 'user' ? (
              <div key={i} className="bubble user">
                {m.content}
              </div>
            ) : (
              renderAssistant(m, i)
            ),
          )}
          {reply !== null && (
            <div className="bubble assistant">
              <span className="forge-tag">点石 · 开发线</span>
              {reply || (sending ? '分身传话中…' : '')}
            </div>
          )}
        </div>
      </main>

      <form className="chat-input" onSubmit={handleSend}>
        <input
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={pendingQ ? '回答分身的问话…' : '和分身说点什么…'}
          disabled={sending}
        />
        <button type="submit" disabled={sending || !input.trim()}>
          发送
        </button>
      </form>
    </div>
  );
}
