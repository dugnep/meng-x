import { useEffect, useState } from 'react';
import { api, getToken } from '../api';
import FeatureCard, { STATUS_LABELS } from '../components/FeatureCard';
import PrdModal from '../components/PrdModal';

/** 断点列数（单卡峰值约 400px） */
const colsForWidth = (w) =>
  w >= 2501 ? 7 : w >= 2101 ? 6 : w >= 1701 ? 5 : w >= 1281 ? 4 : w >= 851 ? 3 : w >= 641 ? 2 : 1;

/**
 * JS 分列瀑布流：CSS columns 是纵向逐列填充，卡片少时右侧列整列留白（大屏右侧
 * 空出两三个卡位的根因）。改为显式分列：列数 = min(断点列数, 卡片数)，
 * round-robin 分配保持左→右阅读顺序，任何卡数都无空白列；
 * 卡片过少时按列数收敛 max-width，防止单卡被拉得过宽（多卡时 max-width 不生效仍拉满）。
 */
function Masonry({ items, empty, renderItem, className = '' }) {
  const [vp, setVp] = useState(window.innerWidth);
  useEffect(() => {
    const onResize = () => setVp(window.innerWidth);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  if (!items || items.length === 0) return empty;
  const n = Math.min(colsForWidth(vp), items.length);
  const cols = Array.from({ length: n }, () => []);
  items.forEach((it, i) => cols[i % n].push([it, i]));
  return (
    <div className={`card-grid ${className}`} style={{ maxWidth: n * 430 + (n - 1) * 18 }}>
      {cols.map((col, ci) => (
        <div className="masonry-col" key={ci}>
          {col.map(([it, i]) => renderItem(it, i))}
        </div>
      ))}
    </div>
  );
}

/**
 * 功能卡看板（设计方案 §3-⑥⑦ §6 P0-5/6）：
 * 全部卡片瀑布流（创意值优先）+ 状态流转 + 导出。
 */
export default function Board({ user, onBack, onOpenForge, onLogout }) {
  const [cards, setCards] = useState([]);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [view, setView] = useState('board'); // board 看板 | wall 修为墙 | mine 我的点子 | rank 创意榜
  const [wallCards, setWallCards] = useState(null); // null=加载中
  const [mineCards, setMineCards] = useState(null);
  const [rankCards, setRankCards] = useState(null);
  const [prdCard, setPrdCard] = useState(null); // 打开 PRD 弹层的卡
  const [wish, setWish] = useState(null); // 许愿池弹层状态：{phase:'input'|'casting'|'done'|'error', text, card, error}

  useEffect(() => {
    const p = new URLSearchParams();
    if (status) p.set('status', status);
    const qs = p.toString();
    api(`/api/idea/cards${qs ? '?' + qs : ''}`)
      .then(setCards)
      .catch((e) => setError(e.message));
  }, [status]);

  // 修为墙：已实现的点子（进入视图时拉取）
  useEffect(() => {
    if (view !== 'wall') return;
    api('/api/idea/cards?status=IMPLEMENTED')
      .then(setWallCards)
      .catch((e) => setError(e.message));
  }, [view]);

  // 我的点子墙：本人历场炼出的卡（进入视图时拉取）
  useEffect(() => {
    if (view !== 'mine') return;
    setMineCards(null);
    api('/api/idea/my-cards').then(setMineCards).catch((e) => setError(e.message));
  }, [view]);

  // 创意榜：点赞最多的点子（进入视图时拉取）
  useEffect(() => {
    if (view !== 'rank') return;
    setRankCards(null);
    api('/api/idea/leaderboard').then(setRankCards).catch((e) => setError(e.message));
  }, [view]);

  function reloadCards() {
    const p = new URLSearchParams();
    if (status) p.set('status', status);
    const qs = p.toString();
    api(`/api/idea/cards${qs ? '?' + qs : ''}`).then(setCards).catch(() => {});
  }

  /** 点赞 toggle：同步更新所在列表的计数与点赞态 */
  async function toggleLike(card, listView) {
    try {
      const r = await api(`/api/idea/cards/${card.id}/like`, { method: 'POST' });
      const apply = (cs) => cs.map((c) => (c.id === card.id ? { ...c, likeCount: r.likeCount, liked: r.liked } : c));
      if (listView === 'board') setCards(apply);
      if (listView === 'rank') setRankCards(apply);
    } catch (err) {
      alert(err.message);
    }
  }

  /** 投愿：精灵两官接力炼卡（同步约 20~60 秒），90 秒超时中止 */
  async function castWish() {
    setWish({ ...wish, phase: 'casting' });
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 90000);
    try {
      const card = await api('/api/idea/wish', {
        method: 'POST',
        body: { content: wish.text.trim() },
        signal: ctrl.signal,
      });
      setWish({ ...wish, phase: 'done', card });
    } catch (err) {
      setWish({
        ...wish,
        phase: 'error',
        error: err.name === 'AbortError' ? '精灵施法超时了，稍后再投一次～' : err.message,
      });
    } finally {
      clearTimeout(timer);
    }
  }

  /** 标记已实现（CONFIRMED → IMPLEMENTED，终态） */
  async function markImplemented(card) {
    try {
      await api(`/api/idea/cards/${card.id}/status`, {
        method: 'PATCH',
        body: { status: 'IMPLEMENTED' },
      });
      reloadCards();
    } catch (err) {
      alert(err.message);
    }
  }

  /** 确认并开工（SPROUT）：授权开工，后端自动建造物线工单 */
  async function confirmAndBuild(card) {
    try {
      await api(`/api/idea/cards/${card.id}/confirm`, {
        method: 'POST',
        body: { sessionId: card.sessionId },
      });
      reloadCards();
    } catch (err) {
      alert(err.message);
    }
  }

  /** PRD 车间开工：格式化官单次出稿（约 15~40 秒），分几轮定时刷新看结果 */
  async function requestPrd(card) {
    try {
      await api(`/api/idea/cards/${card.id}/prd`, { method: 'POST' });
      setCards((cs) => cs.map((c) => (c.id === card.id ? { ...c, prdStatus: 'RUNNING' } : c)));
      [10000, 25000, 45000, 90000].forEach((ms) =>
        setTimeout(() => {
          const p = new URLSearchParams();
          if (status) p.set('status', status);
          const qs = p.toString();
          api(`/api/idea/cards${qs ? '?' + qs : ''}`).then(setCards).catch(() => {});
        }, ms)
      );
    } catch (err) {
      alert(err.message);
    }
  }

  /** 下载施工包（造物线 DONE 后的 Markdown，可直接投喂 AI 编程工具） */
  async function downloadBuild(c) {
    const resp = await fetch(`/api/idea/cards/${c.id}/build`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    });
    if (!resp.ok) {
      alert('施工包还没出稿或已失效，请稍后再试');
      return;
    }
    const blob = await resp.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `施工包-${c.title}.md`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  async function download(params, filename) {
    const resp = await fetch(`/api/idea/export${params ? '?' + params : ''}`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    });
    if (!resp.ok) {
      alert('导出失败，请重试');
      return;
    }
    const blob = await resp.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <div className="board-page">
      <header className="board-header">
        <span className="board-title">点子铺 · 功能卡看板</span>
        <div className="board-header-actions">
          <span className="board-user">{user.nickname}</span>
          <button type="button" className="icon-btn" title="回首页聊" aria-label="回首页聊" onClick={onBack}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M3 10.5L12 3l9 7.5" />
              <path d="M5 9.5V21h5v-6h4v6h5V9.5" />
            </svg>
          </button>
          <button className="logout-btn" onClick={onLogout}>
            退出
          </button>
        </div>
      </header>

      {/* 工具栏：主 tab 一行，看板的筛选/操作在第二行（子筛选行） */}
      <div className="board-toolbar">
        <div className="view-tabs">
          <button className={`chip ${view === 'board' ? 'active' : ''}`} onClick={() => setView('board')}>
            看板
          </button>
          <button className={`chip ${view === 'wall' ? 'active' : ''}`} onClick={() => setView('wall')}>
            修为墙
          </button>
          <button className={`chip ${view === 'mine' ? 'active' : ''}`} onClick={() => setView('mine')}>
            我的点子
          </button>
          <button className={`chip ${view === 'rank' ? 'active' : ''}`} onClick={() => setView('rank')}>
            创意榜
          </button>
        </div>
        {view === 'board' && (
          <div className="board-subtoolbar">
            <div className="status-tabs">
              <button className={`sub-chip ${status === '' ? 'active' : ''}`} onClick={() => setStatus('')}>
                全部
              </button>
              {Object.entries(STATUS_LABELS).map(([k, v]) => (
                <button key={k} className={`sub-chip ${status === k ? 'active' : ''}`} onClick={() => setStatus(k)}>
                  {v}
                </button>
              ))}
            </div>
            <div className="toolbar-actions">
              <button
                className="toolbar-btn wish-entry-btn"
                title="把一句话愿望投进池子，精灵替你炼成功能卡"
                onClick={() => setWish({ phase: 'input', text: '' })}
              >
                <span className="wish-star" aria-hidden="true">✦</span>
                许愿池
              </button>
              <button className="toolbar-btn" onClick={() => download('', '点子铺-功能卡清单.md')}>
                导出全部
              </button>
            </div>
          </div>
        )}
      </div>

      {error && <p className="board-error">{error}</p>}

      {view === 'board' && (
        <Masonry
          items={cards}
          empty={<p className="board-empty">还没有功能卡。回聊天页和百晓通聊聊，点子就长出来了～</p>}
          renderItem={(c) => (
            <FeatureCard
              key={c.id}
              card={c}
              onExport={() => download(`cardId=${c.id}`, `点子铺-${c.title}.md`)}
              footer={
                <div className="fc-footer">
                  <span className="fc-source">
                    {c.nickname} · {new Date(c.createdAt).toLocaleDateString()}
                  </span>
                  <div className="fc-ops">
                    <button
                      className={`like-btn ${c.liked ? 'liked' : ''}`}
                      title="给这个点子添一炷香"
                      onClick={() => toggleLike(c, 'board')}
                    >
                      ❤ {c.likeCount}
                    </button>
                    {c.status === 'SPROUT' && (
                      <button className="fc-btn-confirm" onClick={() => confirmAndBuild(c)}>
                        确认并开工
                      </button>
                    )}
                    {c.status === 'CONFIRMED' && (
                      <>
                        <button onClick={() => markImplemented(c)}>标记已实现</button>
                        {c.buildStatus === 'DONE' && (
                          <button className="rv-open-btn" onClick={() => downloadBuild(c)}>
                            下载施工包
                          </button>
                        )}
                        {c.prdStatus === 'DONE' && (
                          <button className="rv-open-btn" onClick={() => setPrdCard(c)}>
                            查看 PRD
                          </button>
                        )}
                        {c.prdStatus === 'RUNNING' && <button disabled>车间赶工中…</button>}
                        {(c.prdStatus === 'NONE' || c.prdStatus === 'FAILED') && (
                          <button onClick={() => requestPrd(c)}>
                            {c.prdStatus === 'FAILED' ? 'PRD 重新出稿' : 'PRD 车间'}
                          </button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              }
            />
          )}
        />
      )}

      {view === 'wall' && (
        <Masonry
          className="wall-grid"
          items={wallCards}
          empty={
            <>
              {wallCards === null && <p className="board-empty">推开大门看看…</p>}
              {wallCards !== null && wallCards.length === 0 && (
                <p className="board-empty">
                  铺子还在修行中——第一件法宝，正等着从一次闲聊里长出来。
                  <br />
                  和百晓通聊聊，说不定它就是你的点子。
                </p>
              )}
            </>
          }
          renderItem={(c) => (
            <FeatureCard
              key={c.id}
              card={c}
              onExport={() => download(`cardId=${c.id}`, `点子铺-${c.title}.md`)}
              footer={
                <div className="fc-footer">
                  <span className="fc-source wall-source">
                    {c.nickname} 提出 · 修成于 {new Date(c.updatedAt).toLocaleDateString()}
                  </span>
                </div>
              }
            />
          )}
        />
      )}

      {view === 'mine' && (
        <Masonry
          items={mineCards}
          empty={
            <>
              {mineCards === null && <p className="board-empty">翻你的点子簿…</p>}
              {mineCards !== null && mineCards.length === 0 && (
                <p className="board-empty">
                  你还没有留下点子。回聊天页和百晓通聊聊，第一个点子就从这里长出来～
                </p>
              )}
            </>
          }
          renderItem={(c) => (
            <FeatureCard
              key={c.id}
              card={c}
              onExport={() => download(`cardId=${c.id}`, `点子铺-${c.title}.md`)}
              footer={
                <div className="fc-footer">
                  <span className="fc-source">
                    提出于 {new Date(c.createdAt).toLocaleDateString()}
                    {c.status === 'IMPLEMENTED' && ` · 修成于 ${new Date(c.updatedAt).toLocaleDateString()}`}
                    {c.likeCount > 0 && ` · ❤ ${c.likeCount}`}
                  </span>
                </div>
              }
            />
          )}
        />
      )}

      {view === 'rank' && (
        <Masonry
          items={rankCards}
          empty={
            <>
              {rankCards === null && <p className="board-empty">揭示榜单中…</p>}
              {rankCards !== null && rankCards.length === 0 && (
                <p className="board-empty">还没有点子收到香火。去给你喜欢的点子添一炷香，把它顶上榜～</p>
              )}
            </>
          }
          renderItem={(c, i) => (
            <FeatureCard
              key={c.id}
              card={c}
              onExport={() => download(`cardId=${c.id}`, `点子铺-${c.title}.md`)}
              badge={<span className={`rank-no rank-${i + 1}`}>第{i + 1}名</span>}
              footer={
                <div className="fc-footer">
                  <span className="fc-source">
                    {c.nickname} · {new Date(c.createdAt).toLocaleDateString()}
                  </span>
                  <div className="fc-ops">
                    <button
                      className={`like-btn ${c.liked ? 'liked' : ''}`}
                      title="给这个点子添一炷香"
                      onClick={() => toggleLike(c, 'rank')}
                    >
                      ❤ {c.likeCount}
                    </button>
                  </div>
                </div>
              }
            />
          )}
        />
      )}

      {wish && (
        <div className="modal-mask" onClick={() => wish.phase !== 'casting' && setWish(null)}>
          <div className="modal wish-modal" onClick={(e) => e.stopPropagation()}>
            {wish.phase === 'input' && (
              <>
                <h3>许愿池</h3>
                <p className="wish-tip">
                  把一句话愿望投进池子，精灵会替你把它炼成一张功能卡，再回聊天页继续打磨。
                </p>
                <div className="form-row">
                  <textarea
                    value={wish.text}
                    onChange={(e) => setWish({ ...wish, text: e.target.value })}
                    placeholder="比如：想有个能查各地麻将玩法和算钱的本事…"
                    maxLength={200}
                  />
                </div>
                <div className="modal-actions">
                  <button onClick={() => setWish(null)}>先不投了</button>
                  <button className="wish-cast-btn" disabled={!wish.text.trim()} onClick={castWish}>
                    投愿
                  </button>
                </div>
              </>
            )}
            {wish.phase === 'casting' && (
              <>
                <h3>许愿池</h3>
                <p className="wish-casting">精灵施法中…（两位小官接力打磨，约半分钟，别关窗）</p>
              </>
            )}
            {wish.phase === 'done' && (
              <>
                <h3>愿望炼成啦</h3>
                <p className="wish-done">
                  「{wish.card.title}」已收进点子铺（{wish.card.summary}）。
                  回聊天页点「历史」找到这场愿望，可以继续打磨它～
                </p>
                <div className="modal-actions">
                  <button
                    className="wish-cast-btn"
                    onClick={() => {
                      setWish(null);
                      reloadCards();
                    }}
                  >
                    好嘞
                  </button>
                </div>
              </>
            )}
            {wish.phase === 'error' && (
              <>
                <h3>许愿池</h3>
                <p className="wish-error">{wish.error}</p>
                <div className="modal-actions">
                  <button onClick={() => setWish(null)}>先不投了</button>
                  <button className="wish-cast-btn" onClick={() => setWish({ ...wish, phase: 'input' })}>
                    再试一次
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {prdCard && <PrdModal card={prdCard} onClose={() => setPrdCard(null)} />}
    </div>
  );
}
