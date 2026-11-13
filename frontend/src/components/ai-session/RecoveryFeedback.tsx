import { useEffect, useState } from 'react';
import { RotateCw, CircleAlert } from 'lucide-react';
import { recoveryLabel } from '../../lib/aiRecovery';
import type { AiRecovery } from '../../lib/aiRecovery';

export function RecoveryFeedback({ value, active, onStop }: { value: AiRecovery; active?: boolean; onStop: () => void }): JSX.Element {
  const [now, setNow] = useState(Date.now());
  const waiting = active && value.state === 'waiting' && typeof value.nextRetryAt === 'number';
  useEffect(() => {
    if (!waiting) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [waiting, value.nextRetryAt]);
  const running = active && ['waiting', 'connecting'].includes(value.state);
  const label = recoveryLabel(value);
  const remaining = Math.max(0, ((value.nextRetryAt ?? now) - now) / 1000);
  return <div className={`ai-recovery ${running ? 'is-active' : value.state === 'failed' ? 'is-failed' : 'is-stopped'}`}>
    <div className="ai-recovery-head">
      {value.state === 'failed' ? <CircleAlert size={14} aria-hidden="true" /> : <RotateCw size={14} aria-hidden="true" />}
      <span role="status" aria-live="polite">{label}</span>
      {running && <button type="button" onClick={onStop}>停止</button>}
    </div>
    <pre className="ai-recovery-error">{value.error}</pre>
    {running && <span className="ai-recovery-wait">{waiting && remaining > 0
      ? `${remaining >= 10 ? Math.ceil(remaining) : remaining.toFixed(1)} 秒后重试` : '正在连接…'}</span>}
  </div>;
}
