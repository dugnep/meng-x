export const STATUS_LABELS = {
  SPROUT: '新芽',
  CONFIRMED: '已确认',
  IMPLEMENTED: '已实现',
};

export const STATUS_CLASSES = {
  SPROUT: 'sprout',
  CONFIRMED: 'confirmed',
  IMPLEMENTED: 'implemented',
};

/** 优先级徽标配色（高/中/低） */
export const PRIORITY_CLASS = { 高: 'high', 中: 'mid', 低: 'low' };

/** CONFIRMED 卡造物线进展文案（buildStatus → 徽章后缀） */
const BUILD_STATUS_TEXT = {
  RUNNING: '造物中',
  WAITING_USER: '等你澄清',
  REVIEW: '待终审',
  DONE: '施工包已出',
  FAILED: '营造受阻',
  CANCELLED: '已搁置',
};

/** keyItems 类型 → 标签样式类（诉求/疑问/问题） */
const KEYITEM_TAG = { 诉求: 'ask', 疑问: 'q', 问题: 'issue' };

/**
 * 功能卡展示组件（聊天页气泡与看板共用），按新契约 designJson 渲染：
 * 功能清单 / 达成判据 / 实现思路 直出；维度与关键条目折叠；
 * badge：附加徽章（如创意榜名次）；footer：操作区（确认并开工/状态流转等）；
 * onExport：传入则在徽章行末尾渲染导出图标按钮（文档级操作，与流转动作分行）。
 */
export default function FeatureCard({ card, badge, footer, onExport, className = '' }) {
  let design = null;
  try {
    design = card.designJson ? JSON.parse(card.designJson) : null;
  } catch {
    design = null;
  }
  // 「待聊」或缺失的字段视为没有（许愿池炼的卡没有设计内容）
  const clean = (v) => (typeof v === 'string' && v.trim() && v.trim() !== '待聊' ? v : null);
  const functions = Array.isArray(design?.functions) ? design.functions.filter((f) => clean(f)) : [];
  const effects = clean(design?.effects);
  const approach = clean(design?.approach);
  const dims = Array.isArray(design?.dimensions)
    ? design.dimensions.filter((d) => d && clean(d.content))
    : [];
  const constraints = clean(design?.constraints);
  const feasibilityNotes = clean(design?.feasibilityNotes);
  const keyItems = Array.isArray(design?.keyItems) ? design.keyItems.filter((k) => k && k.content) : [];
  const openCount = keyItems.filter((k) => k.status === 'open').length;

  const stars = Math.min(5, Math.max(0, card.creativity || 0));
  // 状态徽章：新芽 / 已确认·造物中（附 buildStatus 文案）/ 已实现
  const statusLabel =
    card.status === 'CONFIRMED' && card.buildStatus && card.buildStatus !== 'NONE'
      ? `已确认 · ${BUILD_STATUS_TEXT[card.buildStatus] || card.buildStatus}`
      : STATUS_LABELS[card.status] || card.status;

  return (
    <div className={`feature-card ${className}`}>
      <div className="fc-badge-row">
        <span className={`fc-badge ${STATUS_CLASSES[card.status] || ''}`}>{statusLabel}</span>
        {card.priority && (
          <span className={`fc-badge prio-${PRIORITY_CLASS[card.priority] || 'mid'}`}>
            优先级{card.priority}
          </span>
        )}
        {badge}
        {onExport && (
          <button type="button" className="fc-export-btn" title="导出施工文档" aria-label="导出施工文档" onClick={onExport}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 3v12" />
              <polyline points="7 10 12 15 17 10" />
              <path d="M5 21h14" />
            </svg>
          </button>
        )}
      </div>
      <div className="fc-title">{card.title}</div>
      <div className="fc-stars">
        {'★'.repeat(stars)}
        {'☆'.repeat(5 - stars)}
      </div>
      {card.summary && <p className="fc-summary">{card.summary}</p>}
      {(functions.length > 0 || effects || approach) && (
        <div className="fc-core">
          {functions.length > 0 && (
            <div className="fc-core-item">
              <span className="fc-core-label">功能清单</span>
              <ul className="fc-fn-list">
                {functions.map((f, i) => (
                  <li key={i}>{f}</li>
                ))}
              </ul>
            </div>
          )}
          {effects && (
            <div className="fc-core-item">
              <span className="fc-core-label">达成判据</span>
              <p className="fc-core-text">{effects}</p>
            </div>
          )}
          {approach && (
            <div className="fc-core-item">
              <span className="fc-core-label">实现思路</span>
              <p className="fc-core-text">{approach}</p>
            </div>
          )}
        </div>
      )}
      {dims.length > 0 && (
        <details className="fc-scroll">
          <summary>设计维度（{dims.length}）</summary>
          <dl className="fc-fields">
            {dims.map((d, i) => (
              <div key={i}>
                <dt>{d.name || `维度${i + 1}`}</dt>
                <dd>{d.content}</dd>
              </div>
            ))}
          </dl>
        </details>
      )}
      {(constraints || feasibilityNotes) && (
        <dl className="fc-fields">
          {constraints && (
            <div>
              <dt>边界与兜底</dt>
              <dd>{constraints}</dd>
            </div>
          )}
          {feasibilityNotes && (
            <div>
              <dt>可行性说明</dt>
              <dd>{feasibilityNotes}</dd>
            </div>
          )}
        </dl>
      )}
      {keyItems.length > 0 && (
        <details className="fc-scroll">
          <summary>关键条目{openCount > 0 ? `（${openCount} 条待定）` : ''}</summary>
          <ul className="fc-keyitems">
            {keyItems.map((k, i) => (
              <li key={i} className={`fc-keyitem${k.status === 'open' ? ' open' : ''}`}>
                <span className={`ki-tag ki-${KEYITEM_TAG[k.type] || 'ask'}`}>{k.type || '条目'}</span>
                <span className="fc-ki-body">
                  {k.content}
                  {k.status === 'open' ? (
                    <i className="fc-ki-status">待定</i>
                  ) : k.conclusion ? (
                    <i className="fc-ki-status resolved">已定：{k.conclusion}</i>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {footer}
    </div>
  );
}
