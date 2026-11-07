import { useEffect, useRef, useState } from 'react';
import { Button, Input, Modal, Spin } from '@arco-design/web-react';
import { apiRequest } from '../../lib/api';
import type { ConnectionProvider, ModelVerification, VerifyModel } from './types';

export function VerificationDetails({ value }: { value: ModelVerification }) {
  return <dl className="connection-checks">{([['text', '文本请求'], ['image', '图片请求（视觉）'], ['tools', '工具调用请求']] as const).map(([key, label]) => {
    const check = value[key];
    return <div key={key}><dt>{label}</dt><dd className={check.status === 'passed' ? 'connection-check-pass' : ''}>{check.status === 'passed' ? '✓ 通过' : check.status === 'skipped' ? '— 未检测' : check.status === 'cancelled' ? '— 已取消' : '本次未通过'}{check.status === 'failed' && <small>{check.message}</small>}</dd></div>;
  })}</dl>;
}

type Row = { model: Record<string, unknown>; status: 'queued' | 'running' | 'added' | 'failed' | 'cancelled'; result?: ModelVerification; error?: string };
interface Props {
  provider: ConnectionProvider; models: Record<string, unknown>[]; password?: string;
  verify: VerifyModel; close: () => void; onBusy: (busy: boolean) => void; refresh: () => void;
}
export function VerificationDialog({ provider, models, password: supplied = '', verify, close, onBusy, refresh }: Props) {
  const [rows, setRows] = useState<Row[]>(() => models.map(model => ({ model, status: 'queued' })));
  const [password, setPassword] = useState(supplied);
  const [running, setRunning] = useState(false);
  const [cancelError, setCancelError] = useState('');
  const cancellation = useRef<Promise<boolean>>();
  const rowsRef = useRef(rows); rowsRef.current = rows;
  const active = useRef<{ controller: AbortController; id: string }>();
  const stopped = useRef(false);
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const patch = (index: number, value: Partial<Row>) => {
    if (!mounted.current) return;
    setRows(current => { const next = current.map((row, i) => i === index ? { ...row, ...value } : row); rowsRef.current = next; return next; });
  };
  const cancel = (): Promise<boolean> => {
    stopped.current = true;
    if (cancellation.current) return cancellation.current;
    const request = active.current;
    if (!request) return Promise.resolve(true);
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);
    cancellation.current = apiRequest(`/api/ai/connections/tests/${request.id}/cancel`, { method: 'POST', signal: controller.signal }).then(() => {
      request.controller.abort(); refresh(); return true;
    }).catch(() => {
      if (mounted.current) setCancelError('取消请求未送达，正在等待当前验证结束。');
      return false;
    }).finally(() => { window.clearTimeout(timeout); cancellation.current = undefined; });
    return cancellation.current;
  };
  const run = async () => {
    if (inFlight.current || (provider.needsPassword && !password)) return;
    inFlight.current = true; stopped.current = false; setRunning(true); setCancelError(''); onBusy(true);
    const batch = rowsRef.current;
    try {
      for (let index = 0; index < batch.length; index++) {
        if (stopped.current) break;
        if (batch[index].status === 'added') continue;
        const controller = new AbortController(), id = crypto.randomUUID(); active.current = { controller, id };
        patch(index, { status: 'running', error: undefined, result: undefined });
        try {
          const value = await verify(batch[index].model, password, id, controller.signal);
          if (!stopped.current) patch(index, { status: value.added ? 'added' : value.cancelled ? 'cancelled' : 'failed', result: value.verification });
        } catch (error) {
          if (!stopped.current) patch(index, { status: 'failed', error: error instanceof Error ? error.message : '验证失败，请重试' });
        } finally { if (active.current?.id === id) active.current = undefined; }
      }
    } finally {
      if (cancellation.current) await cancellation.current;
      inFlight.current = false;
      refresh(); onBusy(false);
      if (mounted.current) { setRunning(false); setRows(current => current.map(row => ['queued', 'running'].includes(row.status) ? { ...row, status: 'cancelled' } : row)); }
    }
  };
  useEffect(() => {
    mounted.current = true;
    // 延后一个任务，避免 StrictMode 的挂载检查产生重复收费请求。
    const timer = window.setTimeout(() => { if (!provider.needsPassword || supplied) void run(); }, 0);
    return () => { window.clearTimeout(timer); mounted.current = false; void cancel(); onBusy(false); };
  }, []);
  const leave = () => { void cancel().then(ok => { if (ok) close(); }); };
  return <Modal visible title={`验证模型 · ${provider.name}`} onCancel={leave} maskClosable={false} footer={<div className="connection-actions end">
    {running ? <Button onClick={() => void cancel()}>取消剩余验证</Button> : <>{rows.some(row => row.status !== 'added') && <Button disabled={provider.needsPassword && !password} onClick={() => void run()}>{rows.every(row => row.status === 'queued') ? '开始验证' : '重试未添加项'}</Button>}<Button type="primary" onClick={leave}>完成</Button></>}
  </div>}>
    <p className="connection-hint">文本通过即可添加；视觉或工具未通过不影响添加。工具通过后自动开启。</p>
    {cancelError && <p role="alert" className="connection-error">{cancelError}</p>}
    {provider.needsPassword && <div className="connection-field"><label htmlFor="verify-password">此连接主密码</label><Input.Password id="verify-password" value={password} onChange={setPassword} disabled={running} /></div>}
    <div className="connection-verifications">{rows.map(row => <section className="connection-verification" key={String(row.model.modelId)}>
      <div className="connection-check-heading"><strong>{String(row.model.name || row.model.modelId)}</strong><span role="status">{row.status === 'running' ? <><Spin size={14} /> 验证中…</> : ({ queued: '待验证', added: '已添加', failed: '未添加', cancelled: '已取消' }[row.status])}</span></div>
      {row.result && <VerificationDetails value={row.result} />}
      {row.status === 'added' && row.result?.tools.status === 'passed' && <p className="connection-hint">工具调用已自动开启。</p>}
      {row.error && <p role="alert" className="connection-error">{row.error}</p>}
    </section>)}</div>
  </Modal>;
}
