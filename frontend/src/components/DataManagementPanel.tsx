import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Empty, Input, Message, Modal, Popconfirm, Select, Spin, Switch, Tag, Typography } from '@arco-design/web-react';
import { Download, FolderOpen, Trash2, Upload } from 'lucide-react';
import { del, get, post, postForm, put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';
import type { BackupConfig, BackupEntry, BackupStatus } from '../lib/types';

const AUTO_OPTIONS = [
  { value: 0, label: '关闭' },
  { value: 12, label: '12 小时' },
  { value: 24, label: '24 小时' },
  { value: 72, label: '3 天' },
  { value: 168, label: '7 天' }
];

const MAX_COUNT_OPTIONS = [1, 3, 5, 10, 20, 30].map((value) => ({ value, label: String(value) }));

function formatSize(sizeBytes: number): string {
  if (sizeBytes >= 1024 * 1024) return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  if (sizeBytes >= 1024) return `${(sizeBytes / 1024).toFixed(0)} KB`;
  return `${sizeBytes} B`;
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/**
 * 数据管理面板：备份配置、备份列表（恢复/删除）、数据导出与导入。
 * 备份 = SQLite VACUUM INTO 快照 +（可选）附件目录；恢复/导入为「暂存 + 重启换库」。
 */
export function DataManagementPanel(): JSX.Element {
  const queryClient = useQueryClient();
  const [directory, setDirectory] = useState('');
  const [autoIntervalHours, setAutoIntervalHours] = useState(24);
  const [maxCount, setMaxCount] = useState(10);
  const [lite, setLite] = useState(false);

  const configQuery = useQuery({ queryKey: ['data-backup-config'], queryFn: () => get<BackupConfig>('/api/data/backup/config') });
  const statusQuery = useQuery({ queryKey: ['data-backup-status'], queryFn: () => get<BackupStatus>('/api/data/backup/status'), refetchInterval: 30_000 });
  const listQuery = useQuery({ queryKey: ['data-backups'], queryFn: () => get<BackupEntry[]>('/api/data/backups') });

  useEffect(() => {
    const config = configQuery.data;
    if (!config) return;
    setDirectory(config.directory ?? '');
    setAutoIntervalHours(config.autoIntervalHours);
    setMaxCount(config.maxCount);
    setLite(config.lite);
  }, [configQuery.data]);

  const invalidateAll = () => {
    void queryClient.invalidateQueries({ queryKey: ['data-backup-config'] });
    void queryClient.invalidateQueries({ queryKey: ['data-backup-status'] });
    void queryClient.invalidateQueries({ queryKey: ['data-backups'] });
  };

  const saveConfigMutation = useMutation({
    mutationFn: (config: BackupConfig) => put<BackupConfig>('/api/data/backup/config', config),
    onSuccess: () => invalidateAll(),
    onError: (error) => Message.error(friendlyMessage(error, '备份设置保存失败，请稍后重试'))
  });

  const saveConfig = (patch: Partial<BackupConfig>) => {
    const config: BackupConfig = {
      directory,
      autoIntervalHours,
      maxCount,
      lite,
      ...patch
    };
    setDirectory(config.directory);
    setAutoIntervalHours(config.autoIntervalHours);
    setMaxCount(config.maxCount);
    setLite(config.lite);
    saveConfigMutation.mutate(config);
  };

  const createBackupMutation = useMutation({
    mutationFn: () => post<BackupEntry>('/api/data/backups', {}),
    onSuccess: (entry) => {
      Message.success(`备份完成：${entry.name}`);
      invalidateAll();
    },
    onError: (error) => {
      Message.error(friendlyMessage(error, '备份失败，请稍后重试'));
      invalidateAll();
    }
  });

  const restoreMutation = useMutation({
    mutationFn: (name: string) => post<Record<string, unknown>>(`/api/data/backups/${encodeURIComponent(name)}/restore`, {}),
    onSuccess: (result) => {
      Message.info(String(result.message ?? '恢复内容已就绪，重启应用后生效'));
      invalidateAll();
    },
    onError: (error) => Message.error(friendlyMessage(error, '恢复失败，请稍后重试'))
  });

  const deleteMutation = useMutation({
    mutationFn: (name: string) => del<void>(`/api/data/backups/${encodeURIComponent(name)}`),
    onSuccess: () => {
      Message.success('备份已删除');
      invalidateAll();
    },
    onError: (error) => Message.error(friendlyMessage(error, '删除备份失败，请稍后重试'))
  });

  const exportMutation = useMutation({
    mutationFn: async () => {
      if (window.api) {
        const directoryPicked = await window.api.dialog.pickDirectory();
        if (!directoryPicked) return null;
        const stamp = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '');
        const baseDir = directoryPicked.replace(/[\\/]+$/, '');
        const sep = baseDir.includes('\\') ? '\\' : '/';
        return post<BackupEntry>('/api/data/backups/export', { targetPath: `${baseDir}${sep}drill-backup-${stamp}.zip` });
      // 浏览器环境无目录选择器：备份到默认备份目录
      const entry = await post<BackupEntry>('/api/data/backups', {});
      return { entry, fallback: true } as const;
    },
    onSuccess: (result) => {
      if (!result) return;
      if ('fallback' in result) {
        Message.info(`已备份到默认备份目录：${result.entry.name}`);
      } else {
        Message.success(`已导出备份包`);
      }
      invalidateAll();
    },
    onError: (error) => Message.error(friendlyMessage(error, '导出失败，请稍后重试'))
  });

  const importMutation = useMutation({
    mutationFn: (filePath: string) => {
      // Electron file:read-file 读出 ArrayBuffer，再以 multipart 交给后端导入
      return window.api!.file.readFile(filePath).then((buffer) => {
        const form = new FormData();
        form.append('file', new Blob([buffer]), filePath.split(/[\\/]/).pop() ?? 'backup.zip');
        return postForm<Record<string, unknown>>('/api/data/backups/import', form);
      });
    },
    onSuccess: (result) => {
      Message.info(String(result.message ?? '导入内容已就绪，重启应用后生效'));
      invalidateAll();
    },
    onError: (error) => Message.error(friendlyMessage(error, '导入失败，请确认选择的是有效的备份 zip 包'))
  });

  const pickAndImport = async () => {
    if (!window.api) {
      Message.warning('请在桌面应用内使用导入功能');
      return;
    }
    const picked = await window.api.file.pickFiles([{ name: '备份包', extensions: ['zip'] }]);
    if (!picked || picked.length === 0) return;
    const file = picked[0];
    Modal.confirm({
      title: '导入备份',
      content: `导入「${file.name}」将在重启应用后替换当前全部笔记数据与附件。确定继续？`,
      okText: '导入',
      cancelText: '取消',
      // 失败已在 onError 提示，吞掉 rejection 避免 Modal 未处理异常
      onOk: () => importMutation.mutateAsync(file.path).catch(() => undefined)
    });
  };

  const openBackupDirectory = async () => {
    const target = statusQuery.data?.directory;
    if (!target || !window.api) return;
    try {
      await window.api.shell.openPath(target);
    } catch (error) {
      Message.error(friendlyMessage(error, '打开备份目录失败'));
    }
  };

  const status = statusQuery.data;

  return (
    <div className="data-manage">
      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>备份管理</h2>
            <p className="muted data-manage-desc">统一管理定期备份、备份目录和备份列表。</p>
            <p className="muted data-manage-desc">
              {status?.running ? (
                <Spin size={14} style={{ marginRight: 6 }} />
              ) : null}
              {status
                ? `${status.autoEnabled ? `自动备份已开启（每 ${status.autoIntervalHours} 小时）` : '自动备份已关闭'} · 最近备份：${formatTime(status.lastBackupAt)}`
                : '正在读取备份状态…'}
            </p>
            {status?.lastError ? <p className="data-manage-error">上次备份出错：{status.lastError}</p> : null}
          </div>
          <div className="data-manage-actions">
            <Button
              type="outline"
              icon={<FolderOpen size={15} />}
              loading={createBackupMutation.isPending}
              onClick={() => createBackupMutation.mutate()}
            >
              立即备份
            </Button>
            {window.api ? (
              <Button type="outline" icon={<FolderOpen size={15} />} onClick={() => void openBackupDirectory()}>
                打开目录
              </Button>
            ) : null}
          </div>
        </div>

        <div className="data-manage-body">
          <div className="data-row">
            <div className="data-row-main">
              <div className="data-row-title">备份目录</div>
              <Input
                className="data-dir-input"
                value={directory}
                placeholder="默认：应用目录/backups"
                allowClear
                onChange={setDirectory}
                onPressEnter={() => saveConfig({ directory: directory.trim() })}
                onBlur={() => {
                  if (directory.trim() !== (configQuery.data?.directory ?? '')) saveConfig({ directory: directory.trim() });
                }}
              />
            </div>
            <div className="data-manage-actions">
              {window.api ? (
                <Button
                  type="outline"
                  size="small"
                  onClick={() => {
                    void window.api!.dialog.pickDirectory().then((picked) => {
                      if (picked) saveConfig({ directory: picked });
                    });
                  }}
                >
                  浏览
                </Button>
              ) : null}
              <Button size="small" onClick={() => saveConfig({ directory: '' })}>
                恢复默认
              </Button>
            </div>
          </div>

          <div className="data-row">
            <div className="data-row-main">
              <div className="data-row-title">自动备份</div>
              <div className="muted data-row-desc">按固定间隔自动创建备份。</div>
            </div>
            <Select
              style={{ width: 130 }}
              value={autoIntervalHours}
              options={AUTO_OPTIONS}
              onChange={(value) => saveConfig({ autoIntervalHours: Number(value) })}
            />
          </div>

          <div className="data-row">
            <div className="data-row-main">
              <div className="data-row-title">最大备份数</div>
              <div className="muted data-row-desc">超出数量的最旧备份会被自动清理。</div>
            </div>
            <Select
              style={{ width: 130 }}
              value={maxCount}
              options={MAX_COUNT_OPTIONS}
              onChange={(value) => saveConfig({ maxCount: Number(value) })}
            />
          </div>

          <div className="data-row">
            <div className="data-row-main">
              <div className="data-row-title">精简备份</div>
              <div className="muted data-row-desc">
                备份时跳过附件文件，仅备份笔记数据库。减少空间占用，加快备份速度。
              </div>
            </div>
            <Switch checked={lite} onChange={(value) => saveConfig({ lite: value })} />
          </div>
        </div>
      </div>

      <div className="panel">
        <div className="panel-header">
          <h2>备份文件</h2>
          <Typography.Text type="secondary" className="muted">
            {listQuery.data ? `${listQuery.data.length} 个备份` : ''}
          </Typography.Text>
        </div>
        <div className="data-manage-body">
          {listQuery.isLoading ? (
            <div className="empty-state"><Spin /></div>
          ) : !listQuery.data || listQuery.data.length === 0 ? (
            <Empty description="暂无备份，点击「立即备份」创建第一个备份" />
          ) : (
            listQuery.data.map((entry) => (
              <div className="data-backup-item" key={entry.name}>
                <div className="data-backup-info">
                  <span className="data-backup-name" title={entry.name}>{entry.name}</span>
                  <Tag size="small" color={entry.lite ? 'gray' : 'arcoblue'}>{entry.lite ? '精简' : '完整'}</Tag>
                  <span className="muted data-backup-meta">{formatSize(entry.sizeBytes)} · {formatTime(entry.createdAt)}</span>
                </div>
                <div className="data-manage-actions">
                  <Popconfirm
                    title="恢复此备份？"
                    content="重启应用后将替换当前全部笔记数据与附件，且不可撤销。"
                    okText="恢复"
                    cancelText="取消"
                    onOk={() => restoreMutation.mutate(entry.name)}
                  >
                    <Button type="text" size="small" loading={restoreMutation.isPending && restoreMutation.variables === entry.name}>
                      恢复
                    </Button>
                  </Popconfirm>
                  <Popconfirm
                    title="删除此备份？"
                    content="删除后不可恢复。"
                    okText="删除"
                    cancelText="取消"
                    onOk={() => deleteMutation.mutate(entry.name)}
                  >
                    <Button type="text" status="danger" size="small" icon={<Trash2 size={14} />}
                      loading={deleteMutation.isPending && deleteMutation.variables === entry.name}
                    />
                  </Popconfirm>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      <div className="panel">
        <div className="panel-header">
          <div>
            <h2>数据导出</h2>
            <p className="muted data-manage-desc">把笔记数据库与附件导出为单个 zip 备份包，可用于迁移或异地保存。</p>
          </div>
          <div className="data-manage-actions">
            <Button type="outline" icon={<Download size={15} />} loading={exportMutation.isPending} onClick={() => exportMutation.mutate()}>
              导出
            </Button>
          </div>
        </div>
        <div className="data-manage-body">
          <div className="data-row">
            <div className="data-row-main">
              <div className="data-row-title">数据导入</div>
              <div className="muted data-row-desc">选择此前导出的备份 zip 包，重启应用后替换当前数据。</div>
            </div>
            <div className="data-manage-actions">
              <Button type="outline" icon={<Upload size={15} />} loading={importMutation.isPending} onClick={() => void pickAndImport()}>
                导入
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
