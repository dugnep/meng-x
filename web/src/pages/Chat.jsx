import { memo, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, streamIdeaChat } from '../api';
import FeatureCard from '../components/FeatureCard';
import ForgeBell from '../components/ForgeBell';
import Fortune from '../components/Fortune';

const SESSION_KEY = 'mengx_chat_session';

/** 助手消息按 Markdown 渲染（分析档支持标题/加粗/列表/表格）；memo 隔离流式增量重渲染到当前条 */
const AssistantText = memo(function AssistantText({ text }) {
  return (
    <div className="md-body">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{text || ''}</ReactMarkdown>
    </div>
  );
});

/** 应用级点子的九格设计稿（营造图）：key 与后端 AgentSpec 字段对齐 */
const APP_GRIDS = [
  ['positioning', '定位'],
  ['persona', '分身'],
  ['entry', '出场'],
  ['capabilities', '能力'],
  ['agents', '当值'],
  ['tools', '数据工具'],
  ['flow', '流程'],
  ['boundaries', '边界'],
  ['phases', '分期'],
  ['acceptance', '验收'],
];

/** 功能级点子的设计共识（营造图） */
const FEATURE_GRIDS = [
  ['approach', '实现要点'],
  ['boundary', '边界处理'],
  ['acceptance', '验收标准'],
];

/** 推演存档 JSON → 营造图面板模型；旧版纯文本存档解析失败返回 null（不展示面板） */
function toDesignModel(raw, prevTexts) {
  let data;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  const craft = data?.craft;
  const isApp = craft?.spec && Object.keys(craft.spec).length > 0;
  const source = isApp ? craft.spec : craft || {};
  const texts = {};
  const cells = (isApp ? APP_GRIDS : FEATURE_GRIDS).map(([key, label]) => {
    const val = source[key];
    const text = Array.isArray(val) ? val.filter(Boolean).join('；') : val || '';
    texts[key] = text;
    const pending = text === '' || text === '待聊' || text === '（待聊）';
    const fresh = !pending && prevTexts && prevTexts[key] !== undefined && prevTexts[key] !== text;
    return { key, label, text: pending ? '待聊' : text, pending, fresh };
  });
  // 自定义维度：九维之外客人临时新增的格子（同步推演当轮即下发，与九格同排展示）
  if (isApp && Array.isArray(source.extraDimensions)) {
    source.extraDimensions.forEach((d, i) => {
      const key = `extra_${i}`;
      const text = d?.content || '';
      texts[key] = text;
      const pending = text === '' || text === '待聊' || text === '（待聊）';
      const fresh = !pending && prevTexts && prevTexts[key] !== undefined && prevTexts[key] !== text;
      cells.push({
        key,
        label: d?.name || `维度${i + 1}`,
        text: pending ? '待聊' : text,
        pending,
        fresh,
      });
    });
  }
  return { scale: isApp ? '应用' : '功能', agenda: data?.product?.agenda, cells, texts };
}

/**
 * 推演过程面板（类 Trae 思考过程）：等待回复期间逐条展示后端下发的推演阶段，
 * 思考中自动展开（呼吸圆点+步骤淡入）；正文开始输出后自动收起为摘要条，可点击回看。
 */
function ThinkingPanel({ steps, active }) {
  const [open, setOpen] = useState(true);
  useEffect(() => {
    if (!active) setOpen(false);
  }, [active]);
  if (!steps?.length) return null;
  return (
    <div className={`think${active ? ' active' : ''}`}>
      <button type="button" className="think-head" onClick={() => setOpen((v) => !v)}>
        <span className="think-dot" />
        <span className="think-title">{active ? '仙君推演中…' : `已推演 ${steps.length} 步`}</span>
        <span className="think-toggle">{open ? '收起' : '展开'}</span>
      </button>
      {open && (
        <ul className="think-steps">
          {steps.map((s, i) => (
            <li key={i} className="think-step">{s}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * 百晓通访谈页（登录后主界面）：与百晓通流式聊天，
 * 聊透的功能以「功能卡」气泡出现，可确认 / 让我再改改 / 不要这个。
 * 消息持久化在后端（换设备不丢），localStorage 只存当前场指针；
 * 左侧场次栏可查看/切换/删除自己的场次（宽屏常驻可抽拉，窄屏抽屉）。
 */
export default function Chat({ user, onOpenBoard, onOpenForge, onLogout }) {
  const [session, setSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [thinking, setThinking] = useState(false); // 推演阶段进行中（共创推演/路由/检索各节点）
  const [history, setHistory] = useState(null); // null=面板关闭，'loading'=加载中，数组=场次列表
  const [showFortune, setShowFortune] = useState(false);
  const [showAlms, setShowAlms] = useState(false); // 添香火：确认卡片后的赞赏码弹层
  const [thanks, setThanks] = useState(null); // 未读感谢卡（数组，逐张展示；null=无）
  const [mode, setMode] = useState(null); // 正在回答的通道：null=仙官本色 / 'law'=法学助手（仅 typing 文案区分，无全局切换）
  const [design, setDesign] = useState(null); // 营造图面板模型（SSE design 事件 / 历史恢复），null=不展示
  const [designOpen, setDesignOpen] = useState(true);
  const [menuOpen, setMenuOpen] = useState(false); // 顶栏品牌菜单（看板/每日一签折叠在百晓通下）
  const [sidebarOpen, setSidebarOpen] = useState(() => window.innerWidth > 768); // 左侧场次栏：宽屏默认展开可抽拉收起，窄屏为抽屉
  const brandRef = useRef(null);
  const [proposal, setProposal] = useState(null); // 待裁决的应用提案邀请卡（旁路观察器异步产出，轮询补收）
  const pollRef = useRef(null); // notices 轮询定时器（收迟到提案/功能卡）
  const [quizAnswered, setQuizAnswered] = useState({}); // 法学题目卡已选选项（questionId → 字母），交卷前可改选
  // 当前待交卷题组（试卷模式）：从消息尾部回溯最后一组连续题卡；其后已有「交卷」消息则视为已交卷
  const quizGroup = useMemo(() => {
    const qs = [];
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'question') {
        qs.unshift(messages[i].question);
      } else if (qs.length > 0) {
        break;
      }
    }
    if (qs.length === 0) return null;
    const lastQ = messages.map((m) => m.role).lastIndexOf('question');
    const submitted = messages
      .slice(lastQ + 1)
      .some((m) => m.role === 'user' && (m.content || '').startsWith('交卷'));
    return { questions: qs, submitted };
  }, [messages]);
  const prevDesignTexts = useRef({}); // 上一版格子文本，用于 diff 标「新」
  const listRef = useRef(null);
  const taRef = useRef(null);

  // 右侧对话线索：每条有效消息取一条摘要（点击定位回原消息）
  const outlineItems = useMemo(() => {
    const items = [];
    messages.forEach((m, i) => {
      if (m.role === 'user' && m.content) items.push({ idx: i, kind: 'user', text: m.content });
      else if (m.role === 'assistant' && m.content) items.push({ idx: i, kind: 'assistant', text: m.content });
      else if (m.role === 'card') items.push({ idx: i, kind: 'card', text: m.card.title || '功能卡' });
      else if (m.role === 'question') items.push({ idx: i, kind: 'question', text: m.question.stem });
    });
    return items;
  }, [messages]);

  // 进页拉未读感谢卡（点子修成后仙君的答谢），逐张弹层展示
  useEffect(() => {
    api('/api/idea/thanks/unread')
      .then((list) => {
        if (list.length > 0) setThanks(list);
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 关闭当前感谢卡（标已读），还有未读则看下一张 */
  function closeThanks() {
    const cur = thanks[0];
    const rest = thanks.slice(1);
    setThanks(rest.length > 0 ? rest : null);
    api(`/api/idea/thanks/${cur.id}/read`, { method: 'POST' }).catch(() => {});
  }

  /** 开一场新访谈（进入页面 / 会话失效自愈 / 用户点"新开一场"） */
  async function startSession() {
    stopNoticesPolling();
    setProposal(null);
    const s = await api('/api/idea/sessions', { method: 'POST' });
    setSession(s);
    setMessages([]);
    setDesign(null); // 新场清空营造图
    prevDesignTexts.current = {};
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    return s;
  }

  /** 从后端拉一场对话：消息流 + 本场卡片，卡片占位按 id 取最新状态（已丢弃的自动跳过） */
  async function fetchConversation(sessionId) {
    const [msgs, cards] = await Promise.all([
      api(`/api/idea/sessions/${sessionId}/messages`),
      api(`/api/idea/sessions/${sessionId}/cards`),
    ]);
    const cardMap = new Map(cards.map((c) => [c.id, c]));
    // question 角色是题组信封（{topic, questions}），拆成逐题气泡恢复到对话流
    return msgs.flatMap((m) => {
      if (m.role === 'card') {
        return cardMap.get(m.cardId) ? [{ role: 'card', card: cardMap.get(m.cardId) }] : [];
      }
      if (m.role === 'question') {
        try {
          return JSON.parse(m.content).questions.map((q) => ({ role: 'question', question: q }));
        } catch {
          return [];
        }
      }
      return [{ role: m.role, content: m.content }];
    });
  }

  /** 切到某一场（含进页恢复）：拉取成功才切换，失败由调用方处理；
   *  notices 一并拉取——上次刷新前留下的待裁决提案，恢复后直接展示 */
  async function openSession(sessionId) {
    stopNoticesPolling();
    setProposal(null);
    const [list, designRes, noticesRes] = await Promise.all([
      fetchConversation(sessionId),
      api(`/api/idea/sessions/${sessionId}/design`).catch(() => null), // 无存档/旧版格式均静默
      api(`/api/idea/sessions/${sessionId}/notices`).catch(() => null),
    ]);
    const model = designRes?.design ? toDesignModel(designRes.design, {}) : null;
    prevDesignTexts.current = model ? model.texts : {};
    setDesign(model);
    setProposal(noticesRes?.proposals?.[0] || null);
    const s = { sessionId, nickname: user.nickname };
    setSession(s);
    setMessages(list);
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
  }

  // 恢复当前场；会话失效（404/被删）自动新开一场
  useEffect(() => {
    const saved = localStorage.getItem(SESSION_KEY);
    const sessionId = saved ? JSON.parse(saved).sessionId : null;
    if (sessionId) {
      openSession(sessionId).catch(() => startSession().catch(() => {}));
    } else {
      startSession().catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 宽屏侧栏默认展开：进场即拉取场次列表（窄屏抽屉保持首次展开时才拉取）
  useEffect(() => {
    if (window.innerWidth > 768) openHistory();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 滚动到底
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const appendLast = (text) =>
    setMessages((prev) => {
      const next = [...prev];
      const last = next[next.length - 1];
      next[next.length - 1] = { ...last, content: (last?.content || '') + text };
      return next;
    });

  /** design 事件/历史恢复共用：推演存档 → 营造图面板模型（解析失败自动跳过）；
   *  只更新内容不强制展开——首次进场由 designOpen 初始值展开，之后尊重用户手动收起状态 */
  function applyDesign(raw) {
    const model = toDesignModel(raw, prevDesignTexts.current);
    if (!model) return;
    prevDesignTexts.current = model.texts;
    setDesign(model);
  }

  /** SSE 事件统一分发（发送与出场共用） */
  function handleEvent(ev) {
    if (ev.type === 'chunk') {
      setThinking(false);
      appendLast(ev.content);
    } else if (ev.type === 'thinking') {
      // 推演阶段：累积到当前 assistant 消息（counsel 现场推演/路由/检索/酝酿各节点）
      setThinking(true);
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, steps: [...(last.steps || []), ev.content] };
        }
        return next;
      });
    } else if (ev.type === 'citations') {
      // 法条引用气泡：先于正文到达，挂到当前 assistant 消息上（刷新不恢复，正文已含条号引用）
      const citations = JSON.parse(ev.content || '[]');
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = { ...last, citations };
        }
        return next;
      });
    } else if (ev.type === 'design') {
      // 营造图：推演官当轮同步产出快照直发，面板当轮点亮共创进展
      applyDesign(ev.content);
    } else if (ev.type === 'question') {
      // 法学出题：题目卡气泡（后端逐题一条事件）
      setMessages((prev) => [...prev, { role: 'question', question: JSON.parse(ev.content) }]);
    } else if (ev.type === 'card') {
      setMessages((prev) => [...prev, { role: 'card', card: JSON.parse(ev.content) }]);
    } else if (ev.type === 'error') {
      appendLast(`\n⚠️ ${ev.content || '出错了'}`);
    }
  }

  /** 停止通知轮询（提案已收到 / 切场 / 新开一场 / 卸载） */
  function stopNoticesPolling() {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }

  /**
   * 通知轮询：机会观察器在旁路线程异步判定，SSE 关流后前端拿不到结果，
   * 轮询 notices 补收两类迟到产物——提案邀请卡（收到即停）与宣布定稿后晚到的功能卡（按 id 去重追加）。
   * 8 秒一次、30 秒超时自动停；切场/新开一场由调用方清理。
   */
  function startNoticesPolling(sessionId) {
    stopNoticesPolling();
    const startedAt = Date.now();
    pollRef.current = setInterval(async () => {
      if (Date.now() - startedAt > 30000) {
        stopNoticesPolling();
        return;
      }
      try {
        const data = await api(`/api/idea/sessions/${sessionId}/notices`);
        if (data.proposals?.length > 0) {
          setProposal(data.proposals[0]);
          stopNoticesPolling();
          return;
        }
        if (data.cards?.length > 0) {
          setMessages((prev) => {
            const known = new Set(prev.filter((m) => m.role === 'card').map((m) => m.card.id));
            const fresh = data.cards.filter((c) => !known.has(c.id));
            return fresh.length ? [...prev, ...fresh.map((card) => ({ role: 'card', card }))] : prev;
          });
        }
      } catch {
        // 网络抖动静默，等下一轮
      }
    }, 8000);
  }

  /** 接下提案：许可后端进入发现模式，开场白气泡即时展示（零模型调用秒回）；
   *  营造图待用户首轮发言、推演官同步产出第一版快照时当轮点亮（accept 时无新信息不推演） */
  async function acceptProposal() {
    if (!proposal || !session) return;
    const { id } = proposal;
    try {
      const data = await api(`/api/idea/proposals/${id}/accept`, { method: 'POST' });
      setProposal(null);
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: data?.opening || '好，那这个点子咱们摊开来聊——先说说你想拿它做什么？' },
      ]);
    } catch (err) {
      setProposal(null);
      alert(err.message);
    }
  }

  /** 婉拒提案：归档后继续正常陪聊（失败静默，提案下次进场不再出现由后端静默归档兜底） */
  function dismissProposal() {
    if (!proposal) return;
    const { id } = proposal;
    setProposal(null);
    api(`/api/idea/proposals/${id}/dismiss`, { method: 'POST' }).catch(() => {});
  }

  async function sendVia(targetMode, text) {
    if (!text || sending || !session) return;
    // 后端只收 user/assistant 文本，卡片消息不上传
    const history = [
      ...messages
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({ role: m.role, content: m.content })),
      { role: 'user', content: text },
    ];
    setInput('');
    setSending(true);
    setMode(targetMode); // 本条消息的通道：law=请法学助手答，答完回落仙官
    setMessages((prev) => [...prev, { role: 'user', content: text }, { role: 'assistant', content: '' }]);
    // 一轮一个幂等键：网络重发/双击重试用同一键，后端只落库一次（secure context 用 UUID，旧环境降级随机串）
    const clientRoundId = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : `r-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    try {
      await streamIdeaChat(session.sessionId, history, targetMode, handleEvent, false, clientRoundId);
    } catch (err) {
      appendLast(`\n⚠️ ${err.message}`);
    } finally {
      setSending(false);
      setThinking(false);
      setMode(null);
      // 收口后启动通知轮询：补收旁路观察器的迟到提案/晚到的功能卡
      startNoticesPolling(session.sessionId);
    }
  }

  async function handleSend(e) {
    e.preventDefault();
    if (sending || !session) return;
    await sendVia(null, input.trim()); // mode=null：由后端意图路由自动分发（法学→法学助手，其余→仙官）
  }

  // 输入框随内容自动增高（上限由 CSS/JS 限制）
  useEffect(() => {
    const el = taRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
  }, [input]);

  /** 线索点击：平滑滚动定位到对应消息 */
  function jumpTo(idx) {
    document.getElementById(`msg-${idx}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** Enter 发送（输入法组词中不触发），Shift+Enter 换行 */
  function handleComposerKey(e) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend(e);
    }
  }

  /** 左侧场次栏开合：首次展开时才拉取列表 */
  function toggleSidebar() {
    const next = !sidebarOpen;
    setSidebarOpen(next);
    if (next && history === null) openHistory();
  }

  /** 法学助手出场：输入框上方「法」浮框点击直接触发开场白，用户不发消息、无 user 气泡，浮框常驻 */
  async function greetLaw() {
    if (sending || !session) return;
    const history = messages
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map((m) => ({ role: m.role, content: m.content }));
    setSending(true);
    setMode('law');
    setMessages((prev) => [...prev, { role: 'assistant', content: '' }]);
    try {
      await streamIdeaChat(session.sessionId, history, 'law', handleEvent, true);
    } catch (err) {
      appendLast(`\n⚠️ ${err.message}`);
    } finally {
      setSending(false);
      setThinking(false);
      setMode(null);
    }
  }

  /**
   * 谈股论道入口占位：多智能体股票分析道场营造中，固定文案提示（零模型调用、不落库）。
   * 待道场开张后，参照 greetLaw 接入后端 L0 显式直达（greet + 专用通道）。
   */
  function greetStock() {
    if (sending) return;
    setMessages((prev) => [
      ...prev,
      { role: 'assistant', content: '谈股论道的道场尚在营造中——班子还在集结。待道场开张，请君来论市。' },
    ]);
  }

  /** 确认功能卡：新芽 → 已确认，盖章动效 + 百晓通继续引导 */
  async function confirmCard(idx) {
    const { card } = messages[idx];
    try {
      const updated = await api(`/api/idea/cards/${card.id}/confirm`, {
        method: 'POST',
        body: { sessionId: session.sessionId },
      });
      setMessages((prev) => {
        const next = [...prev];
        next[idx] = { role: 'card', card: updated, stamped: true };
        next.push({
          role: 'assistant',
          content: `收到！「${updated.title}」已经记进点子铺啦 🎉 我们继续——下一个灵感藏在哪？`,
        });
        return next;
      });
      // 香火钱频控：一场只弹一次
      const almsKey = `mengx_alms_shown_${session.sessionId}`;
      if (!localStorage.getItem(almsKey)) {
        localStorage.setItem(almsKey, '1');
        setShowAlms(true);
      }
    } catch (err) {
      alert(err.message);
    }
  }

  /** 不要这个：从对话中移除并删除 */
  async function discardCard(idx) {
    const { card } = messages[idx];
    try {
      await api(`/api/idea/cards/${card.id}?sessionId=${session.sessionId}`, { method: 'DELETE' });
      setMessages((prev) => prev.filter((_, i) => i !== idx));
    } catch (err) {
      alert(err.message);
    }
  }

  /** 让我再改改：卡片转待修改态，引导继续在输入框说想法 */
  function reviseCard(idx) {
    setMessages((prev) => {
      const next = [...prev];
      next[idx] = { ...next[idx], revising: true };
      return next;
    });
    taRef.current?.focus();
  }

  /** 打开历史面板 */
  async function openHistory() {
    setMenuOpen(false);
    setHistory('loading');
    try {
      setHistory(await api('/api/idea/sessions'));
    } catch (err) {
      setHistory(null);
      alert(`加载失败：${err.message}`);
    }
  }

  // 品牌菜单展开时点击菜单外任意处收起
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onDoc = (e) => {
      if (!brandRef.current?.contains(e.target)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [menuOpen]);

  // 卸载时停止通知轮询
  useEffect(() => () => stopNoticesPolling(), []);

  /** 从侧栏切回某一场 */
  async function switchSession(item) {
    try {
      await openSession(item.sessionId);
      setInput('');
      taRef.current?.focus();
      // 窄屏抽屉：选完即收起；宽屏保持展开
      if (window.matchMedia('(max-width: 768px)').matches) setSidebarOpen(false);
    } catch (err) {
      alert(`进入失败：${err.message}`);
    }
  }

  /** 删除场次（连同消息与卡片）；删的是当前场则自动新开一场 */
  async function removeSession(item) {
    if (!window.confirm(`删除「${item.preview}」？这场聊出的功能卡也会一起删除`)) return;
    try {
      await api(`/api/idea/sessions/${item.sessionId}`, { method: 'DELETE' });
      if (session?.sessionId === item.sessionId) {
        startSession().catch(() => {});
      }
      setHistory(await api('/api/idea/sessions')); // 列表在侧栏里就地刷新
      if (window.matchMedia('(max-width: 768px)').matches) setSidebarOpen(false);
    } catch (err) {
      alert(`删除失败：${err.message}`);
    }
  }

  const showTyping =
    sending && messages[messages.length - 1]?.role === 'assistant' && !messages[messages.length - 1].content
    && !messages[messages.length - 1].steps?.length;

  function renderCard(m, i) {
    const confirmed = m.card.status === 'CONFIRMED';
    return (
      <div key={i} id={`msg-${i}`} className={`fc-wrap ${m.stamped ? 'card-stamped' : ''}`}>
        <FeatureCard
          card={m.card}
          className="fc-in-chat"
          footer={
            m.revising && !confirmed ? (
              <p className="fc-revise-note">在下面告诉百晓通这张卡哪里要改～</p>
            ) : confirmed ? (
              <p className="fc-confirmed-note">✓ 已收进点子铺</p>
            ) : (
              <div className="fc-actions">
                <button className="fc-btn-confirm" onClick={() => confirmCard(i)}>
                  ✓ 确认这张卡
                </button>
                <button onClick={() => reviseCard(i)}>让我再改改</button>
                <button onClick={() => discardCard(i)}>不要这个</button>
              </div>
            )
          }
        />
      </div>
    );
  }

  /** 法学题目卡（试卷模式）：点选仅本地暂存（交卷前可改选，逐题到达期间即可先选），交卷后锁定；
   *  多选/不定项可点多个选项（toggle），单选互斥；主观题为多行作答框 */
  function renderQuiz(m, i) {
    const q = m.question;
    const picked = quizAnswered[q.id] || '';
    const subjective = q.type === '主观';
    const multi = q.type === '多选' || q.type === '不定项';
    const locked = quizGroup && quizGroup.submitted;
    const pick = (key) => {
      setQuizAnswered((prev) => {
        if (!multi) {
          return { ...prev, [q.id]: key };
        }
        const cur = prev[q.id] || '';
        const next = cur.includes(key) ? cur.replace(key, '') : cur + key;
        return { ...prev, [q.id]: next.split('').sort().join('') };
      });
    };
    return (
      <div key={i} id={`msg-${i}`} className="quiz-card">
        {q.no ? <div className="quiz-no">第 {q.no} 题{q.type && q.type !== '单选' ? `（${q.type}${multi ? '，可多选' : ''}）` : ''}</div> : null}
        <div className="quiz-meta">
          {q.knowledgePoints?.map((kp) => (
            <span key={kp} className="quiz-kp">{kp}</span>
          ))}
          {q.difficulty && <span className="quiz-diff">难度 {q.difficulty}</span>}
        </div>
        <p className="quiz-stem">{q.stem}</p>
        {subjective ? (
          <>
            <textarea
              className="quiz-text"
              value={picked}
              disabled={locked}
              rows={8}
              maxLength={5000}
              placeholder="写下你的答案（法言法语，要点作答）…"
              onChange={(e) => setQuizAnswered((prev) => ({ ...prev, [q.id]: e.target.value }))}
            />
            <div className="quiz-wordcount">{picked.length}/5000 字</div>
          </>
        ) : (
          <div className="quiz-opts">
            {q.options?.map((op) => (
              <button
                key={op.key}
                type="button"
                className={`quiz-opt${picked.includes(op.key) ? ' picked' : ''}`}
                disabled={locked}
                onClick={() => pick(op.key)}
              >
                <b>{op.key}.</b> {op.text}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  /** 交卷：主观组单题交作答全文（交卷：<全文>）；客观组按题号拼字母串（交卷：1B 2A 3C）走常规通道 */
  function submitQuiz() {
    if (!quizGroup || quizGroup.submitted || sending) return;
    const qs = quizGroup.questions;
    const text =
      qs.length === 1 && qs[0].type === '主观'
        ? '交卷：' + (quizAnswered[qs[0].id] || '')
        : '交卷：' + qs.map((q, idx) => `${q.no || idx + 1}${quizAnswered[q.id] || ''}`).join(' ');
    sendVia(null, text);
  }

  return (
    <div className="chat-shell">
      {/* 左侧场次栏：宽屏常驻可抽拉收起，窄屏为遮罩抽屉 */}
      <aside className={`chat-aside${sidebarOpen ? ' open' : ''}`}>
        <div className="aside-head">
          <button
            type="button"
            className="aside-new-btn"
            title="新开一场"
            aria-label="新开一场"
            onClick={() => startSession().catch((e) => alert(e.message))}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </button>
        </div>
        <div className="aside-label">历史场次</div>
        <div className="aside-list">
          {history === 'loading' ? (
            <p className="aside-empty">加载中…</p>
          ) : !history || history.length === 0 ? (
            <p className="aside-empty">还没有聊过，快和百晓通聊聊吧～</p>
          ) : (
            history.map((item) => (
              <div
                key={item.sessionId}
                className={`aside-item${session?.sessionId === item.sessionId ? ' active' : ''}`}
              >
                <button className="aside-item-main" onClick={() => switchSession(item)}>
                  <span className="aside-item-title">{item.preview}</span>
                  <span className="aside-item-meta">
                    {item.createdAt} · {item.cardCount} 张功能卡
                  </span>
                </button>
                <button className="aside-item-del" title="删除这场" aria-label="删除这场"
                  onClick={() => removeSession(item)}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <polyline points="3 6 5 6 21 6" />
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
                    <path d="M10 11v6M14 11v6" />
                  </svg>
                </button>
              </div>
            ))
          )}
        </div>
      </aside>
      <div
        className={`aside-mask${sidebarOpen ? ' show' : ''}`}
        onClick={() => setSidebarOpen(false)}
        aria-hidden="true"
      />

      <div className="chat-main">
      <header className="chat-header">
        <button type="button" className="icon-btn side-toggle" title="历史场次" aria-label="历史场次"
          onClick={toggleSidebar}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <line x1="9" y1="4" x2="9" y2="20" />
          </svg>
        </button>
        <div className="brand-menu" ref={brandRef}>
          <button
            type="button"
            className={`brand-toggle${menuOpen ? ' open' : ''}`}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
          >
            <span className="chat-title">百晓通</span>
            <svg className="brand-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          {menuOpen && (
            <div className="brand-pop" role="menu">
              <button type="button" role="menuitem" onClick={() => { setMenuOpen(false); onOpenBoard(); }}>
                看板
              </button>
              <button type="button" role="menuitem" onClick={() => { setMenuOpen(false); setShowFortune(true); }}>
                每日一签
              </button>
            </div>
          )}
        </div>
        <div className="header-actions">
          <ForgeBell onOpenJob={onOpenForge} />
          <button type="button" className="icon-btn" title="新开一场" aria-label="新开一场"
            onClick={() => startSession().catch((e) => alert(e.message))}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </button>
          <button className="logout-btn" onClick={onLogout}>
            退出
          </button>
        </div>
      </header>

      <main className="chat-list" ref={listRef}>
        <div className="chat-col">
        {messages.length === 0 && (
          <p className="chat-empty">
            百晓仙君在铺子里等你——什么都能聊；聊到他还没修成的本事，他会请你一起把它设计出来
          </p>
        )}
        {messages.map((m, i) =>
          m.role === 'card' ? (
            renderCard(m, i)
          ) : m.role === 'question' ? (
            renderQuiz(m, i)
          ) : (
            <div key={i} id={`msg-${i}`} className={`bubble ${m.role}`}>
              {m.role === 'assistant' && m.steps?.length > 0 && (
                <ThinkingPanel
                  steps={m.steps}
                  active={sending && i === messages.length - 1 && !m.content}
                />
              )}
              {m.role === 'assistant' && m.citations?.length > 0 && (
                <div className="law-cites">
                  {m.citations.map((c, j) => (
                    <span key={j} className="law-cite" title={c.snippet}>
                      《{c.lawName}》{c.articleNo}
                    </span>
                  ))}
                </div>
              )}
              {m.role === 'assistant' ? <AssistantText text={m.content} /> : m.content}
              {m.role === 'assistant' && sending && i === messages.length - 1 && m.content && (
                <span className="type-cursor" aria-hidden="true" />
              )}
              {/* 讲评卡尾部的「继续出」按钮：仅讲评是消息流最后一条且已交卷时显示（普通问答/新消息出现即隐） */}
              {m.role === 'assistant' && (m.content || '').startsWith('收卷')
                && quizGroup?.submitted && i === messages.length - 1 && !sending && (
                <div className="quiz-submit-row in-bubble">
                  <button
                    type="button"
                    className="quiz-submit-btn"
                    onClick={() => sendVia(null, '继续出')}
                  >
                    继续出
                  </button>
                </div>
              )}
            </div>
          ),
        )}
        {/* 提案邀请卡：旁路观察器判定「值得一起造个应用」后送达，用户裁决是否开聊 */}
        {proposal && !sending && (
          <div className="proposal-card">
            <p className="proposal-tag">百晓通瞧出了一点机缘</p>
            <p className="proposal-title">「{proposal.suggestTitle}」</p>
            <p className="proposal-story">{proposal.userStory}</p>
            <p className="proposal-gap">眼下这铺子还办不到：{proposal.gap}</p>
            {proposal.signalQuote && (
              <p className="proposal-quote">你说过：「{proposal.signalQuote}」</p>
            )}
            <div className="fc-actions">
              <button className="fc-btn-confirm" onClick={acceptProposal}>
                聊聊看
              </button>
              <button onClick={dismissProposal}>暂不</button>
            </div>
          </div>
        )}
        {showTyping && (
          <div className="bubble assistant typing">
            {mode === 'law' ? '助教翻查典籍中…' : thinking ? '仙君掐指一算中…' : '百晓通琢磨中…'}
          </div>
        )}
        {quizGroup && !quizGroup.submitted && (() => {
          const unselected = quizGroup.questions
            .filter((q) => !(quizAnswered[q.id] || '').trim()).length;
          return (
            <div className="quiz-submit-row">
              <button
                type="button"
                className="quiz-submit-btn"
                disabled={sending || unselected > 0}
                onClick={submitQuiz}
              >
                {unselected > 0 ? `还有 ${unselected} 题未答` : '交卷，请讲评'}
              </button>
            </div>
          );
        })()}
        </div>
      </main>

      {/* 右侧对话线索：悬浮展开，点击定位到历史消息 */}
      {outlineItems.length >= 4 && (
        <nav className="chat-outline" aria-label="对话线索">
          {outlineItems.map((o) => (
            <button
              key={o.idx}
              type="button"
              className={`outline-item k-${o.kind}`}
              title={o.text}
              onClick={() => jumpTo(o.idx)}
            >
              <span className="outline-dot" />
              <span className="outline-text">
                {o.text.replace(/[#*`>[\]~-]/g, ' ').trim().slice(0, 42) || '（空）'}
              </span>
            </button>
          ))}
        </nav>
      )}

      {/* 底部输入区：营造图 + 能力入口 + 输入框收在同一竖列 */}
      <div className="composer-zone">
        {design && (
          <section className="design-panel">
            <button className="design-head" onClick={() => setDesignOpen((v) => !v)}>
              <span className="design-title">营造图</span>
              <span className="design-scale">{design.scale === '应用' ? '新道场 · 应用' : '小件 · 功能'}</span>
              {design.agenda && <span className="design-agenda">本轮推进：{design.agenda}</span>}
              <span className="design-toggle">{designOpen ? '收起' : '展开'}</span>
            </button>
            {designOpen && (
              <div className="design-grid">
                {design.cells.map((c) => (
                  <div key={c.key} className={`design-cell${c.pending ? ' pending' : ''}${c.fresh ? ' fresh' : ''}`}>
                    <span className="design-cell-label">
                      {c.label}
                      {c.fresh && <i className="design-fresh">新</i>}
                    </span>
                    <p className="design-cell-text">{c.text}</p>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}

        <div className="hint-row">
          <div
            className={`law-hint hint-law${sending ? ' disabled' : ''}`}
            onClick={greetLaw}
            title="点击请出法学助手"
          >
            <span className="law-hint-badge">法</span>
            <span className="law-hint-text">法学助手</span>
          </div>
          <div
            className={`law-hint hint-stock${sending ? ' disabled' : ''}`}
            onClick={greetStock}
            title="谈股论道 · 道场营造中"
          >
            <span className="law-hint-badge dao">道</span>
            <span className="law-hint-text">谈股论道</span>
          </div>
        </div>

        <form className="composer" onSubmit={handleSend}>
          <textarea
            ref={taRef}
            rows={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleComposerKey}
            placeholder={messages.some((m) => m.revising) ? '说说这张卡想怎么改…' : '和百晓通聊聊你想要的…'}
            disabled={sending}
          />
          <button type="submit" className="composer-send" disabled={sending || !input.trim()} aria-label="发送">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="12" y1="19" x2="12" y2="5" />
              <polyline points="5 12 12 5 19 12" />
            </svg>
          </button>
        </form>
      </div>
      </div>

      {showFortune && <Fortune onClose={() => setShowFortune(false)} />}

      {showAlms && (
        <div className="modal-mask" onClick={() => setShowAlms(false)}>
          <div className="modal alms-modal" onClick={(e) => e.stopPropagation()}>
            <h3>添点香火</h3>
            <p className="alms-text">
              好点子落进铺子不易，施主若愿意，添点香火钱，助仙君早成正果。全凭心意，不强求。
            </p>
            <img className="alms-qr" src="/alms.jpg" alt="赞赏码" />
            <button className="alms-close" onClick={() => setShowAlms(false)}>
              心领了
            </button>
          </div>
        </div>
      )}

      {thanks && (
        <div className="modal-mask" onClick={closeThanks}>
          <div className="modal thanks-modal" onClick={(e) => e.stopPropagation()}>
            <h3>仙君的感谢卡</h3>
            <p className="thanks-title">《{thanks[0].cardTitle}》</p>
            <p className="thanks-content">{thanks[0].content}</p>
            <p className="thanks-date">点子提出于 {thanks[0].proposedAt}，如今已修成</p>
            <button className="alms-close" onClick={closeThanks}>
              {thanks.length > 1 ? '再看下一张' : '心领了'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
