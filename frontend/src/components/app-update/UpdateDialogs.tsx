import { useEffect, useRef, useState } from 'react';
import { Button, Modal, Spin, Tag } from '@arco-design/web-react';
import { Check, ChevronDown, ExternalLink, LoaderCircle } from 'lucide-react';
import { gt } from 'semver';
import type { AppUpdateState, AppUpdaterApi, NotesRange, UpdateNotes, UpdatePresentation } from '../../../../electron/update-types';
import { MarkdownContent } from '../markdown/MarkdownRenderer';
import { openUpdateLink, PROJECT_URL } from './AboutPanel';
import { updateAction } from './AppUpdateProvider';

const ranges: NotesRange[] = ['since', 10, 20, 50, 100];
const rangeLabel = (range: NotesRange): string => range === 'since' ? '上次更新以来' : `当前版本及之前 ${range} 个版本`;

function RangeMenu({ value, onChange }: { value: NotesRange; onChange: (value: NotesRange) => void }): JSX.Element {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const root = useRef<HTMLDivElement>(null);
  const items = useRef<Array<HTMLButtonElement | null>>([]);
  const close = (focus: boolean): void => { setOpen(false); if (focus) anchor.current?.focus(); };
  useEffect(() => {
    if (!open) return;
    items.current[ranges.indexOf(value)]?.focus();
    const outside = (event: PointerEvent): void => { if (event.target instanceof Node && !root.current?.contains(event.target)) close(false); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
  return <div className="update-range" ref={root}>
    <button ref={anchor} type="button" className="update-range-pill" aria-haspopup="menu" aria-expanded={open} aria-label={`显示的更新说明：${rangeLabel(value)}`}
      onClick={() => setOpen(!open)} onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setOpen(true); }
      }}>{rangeLabel(value)}<ChevronDown size={14} /></button>
    {open && <div className="update-range-menu" role="menu" aria-label="显示的更新说明">
      <div className="update-range-heading">显示的更新说明</div>
      {ranges.map((range, index) => <button key={range} ref={element => { items.current[index] = element; }} type="button" role="menuitemradio"
        aria-checked={value === range} tabIndex={value === range ? 0 : -1} onClick={() => { onChange(range); close(true); }}
        onKeyDown={event => {
          let next: number | undefined;
          if (event.key === 'ArrowDown') next = (index + 1) % ranges.length;
          if (event.key === 'ArrowUp') next = (index + ranges.length - 1) % ranges.length;
          if (event.key === 'Home') next = 0;
          if (event.key === 'End') next = ranges.length - 1;
          if (next !== undefined) { event.preventDefault(); items.current[next]?.focus(); }
          if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); event.nativeEvent.stopImmediatePropagation(); close(true); }
          if (event.key === 'Tab') close(false);
        }}><span>{rangeLabel(range)}</span>{value === range && <Check size={15} aria-hidden="true" />}</button>)}
    </div>}
  </div>;
}

export function ReleaseNotesDialog({ state, api, presentation }: { state: AppUpdateState; api: AppUpdaterApi; presentation: UpdatePresentation }): JSX.Element {
  const history = presentation.kind === 'history';
  const targetVersion = presentation.version;
  const [range, setRange] = useState<NotesRange>('since');
  const [notes, setNotes] = useState<UpdateNotes>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const cached = useRef(new Map<NotesRange, UpdateNotes>());
  useEffect(() => {
    let live = true;
    setLoading(true); setError(''); setNotes(cached.current.get(range));
    void api.notes(range, history ? undefined : targetVersion, retry > 0).then(result => {
      if (!live) return;
      cached.current.set(range, result); setNotes(result); setError(result.error ?? ''); setLoading(false);
    }, error => { if (live) { setError(error instanceof Error ? error.message : '更新内容加载失败。'); setLoading(false); } });
    return () => { live = false; };
  }, [api, range, history, targetVersion, retry]);
  const close = (): void => updateAction(api.closePresentation(presentation.id));
  const releases = notes?.releases ?? [];
  const target = targetVersion ? releases.find(release => release.version === targetVersion) : undefined;
  const titleVersion = history ? releases[0]?.version ?? state.currentVersion : targetVersion;
  const unavailable = state.development ? '开发模式可阅读更新内容；请使用正式便携包更新。'
    : !state.installSupported ? '当前环境不支持便携替换，请通过 GitHub 手动更新。'
      : target && !target.asset ? '此版本暂未提供 Windows x64 便携包，请通过 GitHub 查看。'
        : target && !target.asset?.digest && !target.checksum ? '此版本未提供 SHA-256 校验材料，请通过 GitHub 手动更新。' : '';
  const canInstall = !!target && !unavailable;
  return <Modal visible className="app-update-dialog" title={`更新内容${titleVersion ? ` · v${titleVersion}` : ''}`} style={{ width: 760, maxWidth: 'calc(100vw - 32px)' }}
    maskClosable={false} onCancel={close} footer={history
      ? <Button onClick={close}>关闭</Button>
      : <div className="update-dialog-actions"><Button disabled={!targetVersion} onClick={() => targetVersion && updateAction(api.skip(targetVersion))}>跳过此版本</Button>
        <Button type="primary" disabled={!canInstall} onClick={() => targetVersion && updateAction(api.install(targetVersion))}>立即更新</Button></div>}>
    <div data-app-update>
      {history && <div className="update-notes-toolbar"><RangeMenu value={range} onChange={setRange} /></div>}
      {error && <div className="update-notes-error" role="alert"><span>{error}{notes?.cached && ' 正在显示已缓存的更新内容。'}</span>
        <Button size="small" onClick={() => setRetry(value => value + 1)}>重试</Button></div>}
      {!history && unavailable && <p className="about-update-warning">{unavailable}</p>}
      <div className="update-notes-scroll" aria-busy={loading} onClick={event => {
        const link = event.target instanceof Element ? event.target.closest('a') : null;
        if (!link) return;
        event.preventDefault();
        const url = link.getAttribute('href');
        if (url?.startsWith('https://')) openUpdateLink(url);
      }}>
        {loading && !releases.length && <div className="update-notes-empty" role="status"><Spin />正在加载更新内容…</div>}
        {!loading && !releases.length && <div className="update-notes-empty">{error ? '当前没有可用的缓存内容。' : '所选范围暂无已发布的更新内容。'}
          <Button type="text" onClick={() => openUpdateLink(`${PROJECT_URL}/releases`)}>前往 GitHub Releases</Button></div>}
        {releases.map(release => <article className="update-release" key={release.version}>
          <header><h3>v{release.version}</h3>
            {release.version === state.currentVersion ? <Tag size="small" color="arcoblue">当前安装</Tag>
              : gt(release.version, state.currentVersion) ? <Tag size="small">尚未安装</Tag> : null}
            {release.version === state.skippedVersion && <Tag size="small">已跳过</Tag>}
            <span className="update-release-date">{release.publishedAt ? new Date(release.publishedAt).toLocaleDateString('zh-CN') : ''}</span>
            <Button type="text" size="small" icon={<ExternalLink size={13} />} onClick={() => openUpdateLink(release.url)}>GitHub 原文</Button>
          </header>
          {release.body.trim() ? <MarkdownContent value={release.body} /> : <p className="update-empty-body">此版本未提供更新说明。</p>}
        </article>)}
      </div>
      {history && <p className="update-notes-count">显示 {releases.length} 个版本{typeof range === 'number' ? `，以当前安装版 v${state.currentVersion} 为上限` : ''}。</p>}
    </div>
  </Modal>;
}

const bytes = (value: number): string => value >= 1024 ** 2 ? `${(value / 1024 ** 2).toFixed(1)} MB` : `${(value / 1024).toFixed(1)} KB`;
export function UpdateProgressDialog({ state, api, presentation }: { state: AppUpdateState; api: AppUpdaterApi; presentation: UpdatePresentation }): JSX.Element {
  const progress = state.progress;
  const downloading = progress.phase === 'downloading';
  const failed = progress.phase === 'failed';
  const percent = progress.total ? Math.min(100, Math.floor(progress.received / progress.total * 100)) : undefined;
  const stage = { downloading: '正在下载更新包', verifying: '正在校验更新包', preparing: '正在保存当前工作', blocked: '等待当前操作完成', restarting: '正在准备自动重启', failed: '更新未完成', idle: '下载已取消' }[progress.phase];
  const version = progress.version ?? presentation.version;
  const close = (): void => updateAction(api.closePresentation(presentation.id));
  return <Modal visible className="app-update-dialog app-update-progress-dialog" title={failed ? '更新未完成' : '正在更新 Drill Notebook'}
    style={{ width: 500, maxWidth: 'calc(100vw - 32px)' }} maskClosable={false} onCancel={close}
    footer={failed ? <div className="update-dialog-actions"><Button onClick={() => openUpdateLink(`${PROJECT_URL}/releases`)}>GitHub 原文</Button>
      <Button type="primary" disabled={!version || !state.installSupported} onClick={() => version && updateAction(api.install(version))}>重试更新</Button></div>
      : downloading ? <Button onClick={() => updateAction(api.cancelDownload())}>取消下载</Button>
        : progress.phase === 'blocked' ? <Button onClick={close}>返回处理当前工作</Button> : null}>
    <div data-app-update className="update-progress-content">
      <p className="update-progress-version">v{state.currentVersion} → v{version}</p>
      <h3>{!failed && <LoaderCircle size={17} className="update-spin" />}{stage}</h3>
      {downloading ? <><div className={`update-progress-track${percent === undefined ? ' is-indeterminate' : ''}`} role="progressbar" aria-label="更新包下载进度"
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}><span style={percent === undefined ? undefined : { width: `${percent}%` }} /></div>
        <div className="update-download-stats"><span>{bytes(progress.received)}{progress.total ? ` / ${bytes(progress.total)}` : ''}</span><span>{percent === undefined ? '' : `${percent}%`}</span>
          <span>{bytes(progress.bytesPerSecond)}/s</span></div></> : <p role={failed ? 'alert' : 'status'}>{progress.message ?? '正在准备便携更新…'}</p>}
      {!failed && <p className="update-auto-restart-hint">完成后将自动重启。关闭此窗口只隐藏进度，更新仍会继续。</p>}
      {failed && <p className="update-auto-restart-hint">原程序和数据已保留，可继续使用并稍后重试。</p>}
    </div>
  </Modal>;
}
