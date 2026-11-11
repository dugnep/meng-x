import { useEffect, useState } from 'react';
import { api } from '../api';

const GRADE_CLASS = { '上上': 'ss', '上': 's', '中吉': 'mj', '中平': 'mp' };

/**
 * 每日一签弹层（点子铺长出的第一个功能）：
 * 打开先查今日状态；未抽显示签筒「摇一签」，抽后展示签文；当日重开直接显示已抽的签。
 */
export default function Fortune({ onClose }) {
  const [loading, setLoading] = useState(true);
  const [drawing, setDrawing] = useState(false);
  const [fortune, setFortune] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api('/api/fortune/today')
      .then((t) => {
        setFortune(t.fortune);
        setLoading(false);
      })
      .catch((e) => {
        setError(e.message);
        setLoading(false);
      });
  }, []);

  async function draw() {
    setError('');
    setDrawing(true);
    try {
      const f = await api('/api/fortune/draw', { method: 'POST' });
      // 摇签动效走完再亮签
      setTimeout(() => {
        setFortune(f);
        setDrawing(false);
      }, 900);
    } catch (e) {
      setDrawing(false);
      setError(e.message);
    }
  }

  return (
    <div className="modal-mask" onClick={onClose}>
      <div className="modal fortune-modal" onClick={(e) => e.stopPropagation()}>
        <h3>每日一签</h3>
        {loading && <p className="history-empty">掐算中…</p>}
        {error && <p className="board-error">{error}</p>}
        {!loading && !fortune && !drawing && (
          <div className="fortune-ready">
            <div className="fortune-tube">签</div>
            <p className="fortune-tip">每日一支，上上签正在等你</p>
            <button className="fortune-draw-btn" onClick={draw}>
              摇一签
            </button>
          </div>
        )}
        {drawing && (
          <div className="fortune-ready">
            <div className="fortune-tube shaking">签</div>
            <p className="fortune-tip">竹筒哗啦作响…</p>
          </div>
        )}
        {fortune && (
          <div className="fortune-result">
            <div className={`fortune-grade ${GRADE_CLASS[fortune.grade] || 'mp'}`}>
              {fortune.grade}签
            </div>
            <div className="fortune-title">{fortune.title}</div>
            <p className="fortune-text">{fortune.text}</p>
            <p className="fortune-interpret">{fortune.interpret}</p>
            {!fortune.newDraw && <p className="fortune-again">今日已抽过，明日再来～</p>}
            <p className="fortune-lead">想让产品更合你的手？去和主理助理聊聊，提个新功能吧</p>
            <button className="fortune-close-btn" onClick={onClose}>
              收下这支签
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
