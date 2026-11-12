import { CircleArrowUp, LoaderCircle } from 'lucide-react';
import { BrandMark } from '../BrandMark';
import { updateAction, useAppUpdate } from './AppUpdateProvider';

export function UpdateEntry(): JSX.Element | null {
  const { api, state } = useAppUpdate();
  const updating = state && ['downloading', 'verifying', 'preparing', 'blocked', 'restarting'].includes(state.progress.phase);
  if (!api || !state || (!state.updateVisible && !updating)) return null;
  const version = updating ? state.progress.version : state.target?.version;
  const label = updating ? `正在更新 v${version}，查看进度` : `发现新版本 v${version}，查看更新内容`;
  return <button type="button" className="topbar-update-entry" aria-label={label} title={label} onClick={() => updateAction(api.openAvailable())}>
    <CircleArrowUp size={18} aria-hidden="true" />
  </button>;
}

export function UpdateStartupView(): JSX.Element {
  const { state } = useAppUpdate();
  const phase = state?.progress.phase;
  const caption = phase && ['downloading', 'verifying', 'preparing', 'blocked', 'restarting'].includes(phase)
    ? '正在完成启动更新，完成后会自动重新打开。' : state?.checking ? '正在检查启动更新…' : '正在准备 Drill Notebook…';
  return <div className="update-startup" role="status" aria-live="polite"><BrandMark size={56} /><h2>Drill Notebook</h2><p><LoaderCircle size={16} className="update-spin" />{caption}</p></div>;
}
