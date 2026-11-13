import { useEffect, useState } from 'react';
import { Button, Input, Select, Switch, Tag } from '@arco-design/web-react';
import { FileText, Github, RotateCw, Star } from 'lucide-react';
import { BrandMark } from '../BrandMark';
import { pageContextRows, usePageContextSection } from '../PageContextProvider';
import { updateAction, useAppUpdate } from './AppUpdateProvider';
import type { UpdateSettings } from '../../../../electron/update-types';

export const PROJECT_URL = 'https://github.com/qilin-software/drill-notebook';
export function openUpdateLink(url: string): void {
  if (!/^https:\/\//i.test(url)) return;
  if (window.api?.shell) updateAction(window.api.shell.openExternal(url));
  else window.open(url, '_blank', 'noopener,noreferrer');
}
const presets = [1, 6, 12, 24, 48];
export function AboutPanel(): JSX.Element {
  const { api, state, error } = useAppUpdate();
  const [custom, setCustom] = useState(false);
  const [hours, setHours] = useState(String(state?.settings.intervalHours ?? 24));
  const [intervalError, setIntervalError] = useState('');
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (state) { setHours(String(state.settings.intervalHours)); if (!presets.includes(state.settings.intervalHours)) setCustom(true); }
  }, [state?.settings.intervalHours]);
  const save = async (patch: Partial<UpdateSettings>): Promise<void> => {
    if (!api || saving) return;
    setSaving(true);
    try { await api.setSettings(patch); }
    catch (error) { throw error; }
    finally { setSaving(false); }
  };
  const saveHours = (): void => {
    const value = Number(hours);
    if (!/^\d+$/.test(hours) || !Number.isInteger(value) || value < 1 || value > 168) { setIntervalError('请输入 1–168 的整数小时。'); return; }
    setIntervalError('');
    if (value !== state?.settings.intervalHours) updateAction(save({ intervalHours: value }));
  };
  const settings = state?.settings;
  const disabled = !api || !settings || saving;
  const status = state?.checking ? '正在检查更新…'
    : state?.checkStatus === 'error' ? state.checkError
      : state?.target ? `发现新版本 v${state.target.version}${state.skippedVersion === state.target.version ? '，已跳过此版本' : ''}`
        : state?.checkStatus === 'current' ? '当前已是最新版本' : '尚未检查更新';
  const intervalDraft = custom && hours !== String(settings?.intervalHours ?? 24);
  usePageContextSection('about', [
    pageContextRows('Drill Notebook', [
      ['当前版本', state ? `v${state.currentVersion}` : api ? '正在读取版本…' : '开发预览'],
      ['运行模式', state?.development ? '开发模式' : undefined],
      ['更新检查', error ?? status],
      ['上次成功检查', state?.lastCheckAt ? new Date(state.lastCheckAt).toLocaleString('zh-CN', { hour12: false }) : undefined],
      ['GitHub 主页', PROJECT_URL], ['操作', '检查更新、更新内容']
    ]),
    pageContextRows('更新设置', [
      ['自动更新', settings?.automaticUpdate ?? false], ['自动检查更新', settings?.automaticCheck ?? true],
      ['检查与提醒间隔', intervalDraft ? /^\d+$/.test(hours) ? `${hours} 小时（未保存）` : '自定义输入无效（未保存）' : `${settings?.intervalHours ?? 24} 小时`],
      ['启动时更新提醒', settings?.startupReminder ?? true],
      ['已跳过版本', state?.skippedVersion ? `v${state.skippedVersion}` : undefined],
      ['设置状态', disabled ? '当前不可修改' : '可以修改']
    ]),
    '## 支持项目\n如果 Drill Notebook 对你有帮助，欢迎在 GitHub 点个 Star。'
  ].join('\n\n'), intervalDraft);
  return <div className="settings-tab-stack app-about" role="tabpanel" aria-label="关于">
    <section className="panel">
      <div className="panel-body about-product">
        <div className="about-product-heading">
          <div className="about-brand"><BrandMark size={44} /><div><h2>Drill Notebook</h2>
            <div className="about-version"><span>{state ? `v${state.currentVersion}` : api ? '正在读取版本…' : '开发预览'}</span>
              {state?.development ? <Tag size="small">开发模式</Tag> : state && (state.currentVersion.startsWith('0.') || state.currentVersion.includes('-')) ? <Tag size="small">预发行</Tag> : null}
            </div></div></div>
          <Button icon={<RotateCw size={15} />} loading={state?.checking} disabled={!api} onClick={() => updateAction(api?.check())}>
            {state?.checking ? '检查中…' : '检查更新'}
          </Button>
        </div>
        <div className={`about-check-status${state?.checkStatus === 'error' ? ' is-error' : ''}`} role="status" aria-live="polite">
          <span>{error ?? status}</span>
          {state?.lastCheckAt && <small>上次成功检查：{new Date(state.lastCheckAt).toLocaleString('zh-CN', { hour12: false })}</small>}
        </div>
        {state?.target && !state.target.asset && <p className="about-update-warning">此版本暂未提供匹配的便携包。可在更新内容中查看 GitHub 原文。</p>}
        {state?.failure && <div className="about-update-warning" role="alert"><span>{state.failure}</span>
          {state.failedVersion && <Button type="text" size="small" onClick={() => updateAction(api?.openAvailable())}>查看并重试</Button>}
        </div>}
        <div className="about-links">
          <Button icon={<Github size={16} />} onClick={() => openUpdateLink(PROJECT_URL)}>GitHub 主页</Button>
          <Button icon={<FileText size={16} />} disabled={!api} onClick={() => updateAction(api?.openHistory())}>更新内容</Button>
        </div>
      </div>
    </section>
    <section className="panel">
      <div className="panel-header"><h2>更新设置</h2></div>
      <div className="panel-body about-setting-list">
        <div className="about-setting-row"><div><label htmlFor="about-auto-update">自动更新</label><p>每次启动立即检查，有新版本时自动下载并重启。</p></div>
          <Switch id="about-auto-update" aria-label="自动更新" checked={settings?.automaticUpdate ?? false} disabled={disabled} onChange={value => updateAction(save({ automaticUpdate: value }))} /></div>
        <div className="about-setting-row"><div><label htmlFor="about-auto-check">自动检查更新</label><p>按设定间隔检查新版本，在顶部显示更新入口。</p></div>
          <Switch id="about-auto-check" aria-label="自动检查更新" checked={settings?.automaticCheck ?? true} disabled={disabled} onChange={value => updateAction(save({ automaticCheck: value }))} /></div>
        <div className={`about-setting-row${!settings?.automaticCheck ? ' is-disabled' : ''}`}><div><label id="about-interval-label">检查与提醒间隔</label><p>检查和启动弹窗共用此间隔；频繁重启不会重复提醒。</p></div>
          <div className="about-interval-controls"><Select aria-label="检查与提醒间隔" disabled={disabled || !settings?.automaticCheck}
            value={custom || !presets.includes(settings?.intervalHours ?? 24) ? 'custom' : settings?.intervalHours ?? 24}
            onChange={(value: number | 'custom') => {
              if (value === 'custom') { setCustom(true); return; }
              setCustom(false); setIntervalError(''); updateAction(save({ intervalHours: value }));
            }} style={{ width: 136 }}>
            {presets.map(value => <Select.Option key={value} value={value}>{value} 小时</Select.Option>)}
            <Select.Option value="custom">自定义</Select.Option>
          </Select>
          {custom && <><Input aria-label="自定义间隔小时" value={hours} onChange={value => { setHours(value); setIntervalError(''); }}
            inputMode="numeric" disabled={disabled || !settings?.automaticCheck} style={{ width: 96 }}
            onBlur={saveHours} onKeyDown={event => { if (event.key === 'Enter') saveHours(); }} suffix="小时" />
            {intervalError && <small className="about-interval-error" role="alert">{intervalError}</small>}</>}
          </div></div>
        <div className={`about-setting-row${settings?.automaticUpdate || !settings?.automaticCheck ? ' is-disabled' : ''}`}><div><label htmlFor="about-startup-reminder">启动时更新提醒</label>
          <p>{settings?.automaticUpdate ? '自动更新已开启，启动时直接显示更新进度。' : '有新版且距上次提醒已满设定间隔时，启动后显示更新说明。'}</p></div>
          <Switch id="about-startup-reminder" aria-label="启动时更新提醒" checked={settings?.startupReminder ?? true}
            disabled={disabled || !!settings?.automaticUpdate || !settings?.automaticCheck} onChange={value => updateAction(save({ startupReminder: value }))} /></div>
        {state?.skippedVersion && <div className="about-setting-row"><div><strong>已跳过 v{state.skippedVersion}</strong><p>此版本不再自动提示；仍可手动查看或更新。</p></div>
          <Button type="text" disabled={!api || saving} onClick={() => updateAction(api?.restoreReminder())}>恢复提醒</Button></div>}
      </div>
    </section>
    <section className="panel"><div className="panel-body about-star"><div><h2>支持项目</h2><p>如果 Drill Notebook 对你有帮助，欢迎在 GitHub 点个 Star。</p></div>
      <Button icon={<Star size={16} />} onClick={() => openUpdateLink(PROJECT_URL)}>去 GitHub 点 Star</Button></div></section>
  </div>;
}
