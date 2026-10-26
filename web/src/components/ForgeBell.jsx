import { useEffect, useRef, useState } from 'react';
import { api } from '../api';

/** 工单状态 → 中文标签 / 徽章样式类（与 ForgeChat 共用口径） */
const STATUS_LABELS = {
  RUNNING: '营造中',
  WAITING_USER: '等定夺',
  BLOCKED: '遇阻',
  FAILED: '出岔子',
  REVIEW: '待终审',
  DONE: '已出稿',
  CANCELLED: '已取消',
};

const STATUS_CLASSES = {
  RUNNING: 'st-running',
  WAITING_USER: 'st-waiting',
  BLOCKED: 'st-blocked',
  FAILED: 'st-failed',
  REVIEW: 'st-review',
  DONE: 'st-done',
  CANCELLED: 'st-cancelled',
};

/**
 * 顶栏铃铛（造物线任务面板）：角标 15s 轮询 /api/forge/pending（页面隐藏时跳过），
 * 展开拉在制工单列表，点击工单进入造物线会话；点面板外自动收起。
 */
export default function ForgeBell({ onOpenJob }) {
  const [open, setOpen] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);
  const [jobs, setJobs] = useState(null); // null=加载中
  const wrapRef = useRef(null);

  // 角标轮询：未决问题数之和（隐藏标签页跳过，避免后台空转）
  useEffect(() => {
    let stopped = false;
    const tick = () => {
      if (stopped || document.hidden) return;
      api('/api/forge/pending')
        .then((list) => {
          if (!stopped) setPendingCount(list.reduce((n, j) => n + (j.pendingCount || 0), 0));
        })
        .catch(() => {});
    };
    tick();
    const timer = setInterval(tick, 15000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  // 展开时拉在制工单列表
  useEffect(() => {
    if (!open) return undefined;
    setJobs(null);
    api('/api/forge/jobs').then(setJobs).catch(() => setJobs([]));
    return undefined;
  }, [open]);

  // 点外收起（与品牌菜单同模式）
  useEffect(() => {
    if (!open) return undefined;
    const onDoc = (e) => {
      if (!wrapRef.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  return (
    <div className="forge-bell-wrap" ref={wrapRef}>
      <button
        type="button"
        className={`icon-btn forge-bell-btn${open ? ' open' : ''}`}
        title="造物线工单"
        aria-label="造物线工单"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.7 21a2 2 0 0 1-3.4 0" />
        </svg>
        {pendingCount > 0 && (
          <span className="forge-bell-badge">{pendingCount > 9 ? '9+' : pendingCount}</span>
        )}
      </button>
      {open && (
        <div className="forge-panel">
          <p className="forge-panel-title">造物线 · 在制工单</p>
          {jobs === null ? (
            <p className="forge-panel-empty">翻查工单簿…</p>
          ) : jobs.length === 0 ? (
            <p className="forge-panel-empty">
              还没有开工的道场。功能卡「确认并开工」后，造物线工单会自动建好。
            </p>
          ) : (
            jobs.map((j) => (
              <button
                key={j.jobId}
                type="button"
                className="forge-job-row"
                onClick={() => {
                  setOpen(false);
                  onOpenJob(j.jobId);
                }}
              >
                <span className="forge-job-name">{j.cardTitle}</span>
                <span className="forge-job-side">
                  {j.pendingCount > 0 && (
                    <span className="forge-pending-pill">{j.pendingCount} 个待定夺</span>
                  )}
                  <span className={`forge-badge ${STATUS_CLASSES[j.status] || ''}`}>
                    {STATUS_LABELS[j.status] || j.status}
                  </span>
                </span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
