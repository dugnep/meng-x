import { useState } from 'react';
import { getToken } from '../api';

/**
 * PRD 弹层：格式化官把功能卡与方案卷轴整理出的施工 PRD（纯 Markdown 文本），
 * 可直接复制 / 下载，投喂给 AI 编程工具实现。
 */
export default function PrdModal({ card, onClose }) {
  const [copied, setCopied] = useState(false);

  const prd = card.prdJson || '';

  async function copyMarkdown() {
    try {
      await navigator.clipboard.writeText(prd);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      alert('复制失败，请在正文区域手动全选复制');
    }
  }

  async function download() {
    const resp = await fetch(`/api/idea/cards/${card.id}/prd`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    });
    if (!resp.ok) {
      alert('下载失败，请重试');
      return;
    }
    const blob = await resp.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `PRD-${card.title}.md`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <div className="modal-mask" onClick={onClose}>
      <div className="modal prd-modal" onClick={(e) => e.stopPropagation()}>
        <h3>PRD 车间 · {card.title}</h3>
        {prd ? (
          <pre className="prd-md">{prd}</pre>
        ) : (
          <p className="history-empty">PRD 还没出稿，稍等片刻再来看～</p>
        )}
        <div className="prd-actions">
          <button className="prd-copy-btn" onClick={copyMarkdown} disabled={!prd}>
            {copied ? '已复制' : '复制全文'}
          </button>
          <button className="prd-copy-btn" onClick={download} disabled={!prd}>
            下载 .md
          </button>
          <button className="fortune-close-btn" onClick={onClose}>
            关闭
          </button>
        </div>
      </div>
    </div>
  );
}
