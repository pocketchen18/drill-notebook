import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, DatePicker, Input, Message, Modal, Radio, Select, Space, Typography } from '@arco-design/web-react';
import { Plus } from 'lucide-react';
import { ApiError, get } from '../../lib/api';
import { friendlyMessage } from '../../lib/errors';
import type { Notebook } from '../../lib/types';
import type { MemoryItem, MemoryKind, MemoryScopeType } from '../../lib/aiMemoryTypes';
import {
  addMemoryItem,
  deleteMemoryItem,
  getMemorySettings,
  updateMemorySettings
} from '../../lib/aiMemoryApi';
import { MemoryDetail } from './MemoryDetail';
import { MemoryListView } from './MemoryListView';

/**
 * 记忆管理面板（§12）：把列表、详情编辑、手动新增与删除确认装在一起。
 *
 * <p>只有库已解锁才请求正文；未解锁时界面说的是"需要解锁"，不会伪装成"没有记忆"。
 * 删除与遗忘都要显式确认，forget=true 会同时推进全局遗忘边界，所以它单独一个勾选。</p>
 */

const KIND_OPTIONS: Array<{ value: MemoryKind; label: string }> = [
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'GOAL', label: '目标' },
  { value: 'BACKGROUND', label: '背景' },
  { value: 'TASK', label: '任务' }
];

function newOperationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `mem-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 后端按 Instant.parse 校验有效期，日期选择器要补成完整 UTC 时刻。 */
function toInstant(dateString: string): string | undefined {
  return dateString ? `${dateString.slice(0, 10)}T00:00:00Z` : undefined;
}

export function MemoryManager(): JSX.Element {
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({ queryKey: ['ai-memory-settings'], queryFn: getMemorySettings });
  const notebooksQuery = useQuery({ queryKey: ['notebooks'], queryFn: () => get<Notebook[]>('/api/notebooks') });
  const settings = settingsQuery.data;
  const unlocked = settings?.initialized === true && settings.unlocked === true;

  const [detail, setDetail] = useState<MemoryItem | null>(null);
  const [addVisible, setAddVisible] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MemoryItem | null>(null);
  const [forget, setForget] = useState(false);
  const [kind, setKind] = useState<MemoryKind>('PREFERENCE');
  const [scopeType, setScopeType] = useState<MemoryScopeType>('GLOBAL');
  const [scopeId, setScopeId] = useState<number>(0);
  const [predicate, setPredicate] = useState('');
  const [value, setValue] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [sensitiveConfirmed, setSensitiveConfirmed] = useState(false);
  // 弹窗关掉后要把焦点还给触发它的那个控件，否则键盘用户会掉进页面顶部
  const triggerRef = useRef<Element | null>(null);

  const openWithFocusRestore = (action: () => void): void => {
    triggerRef.current = document.activeElement;
    action();
  };

  const closeAndRestore = (): void => {
    setDetail(null);
    setAddVisible(false);
    setDeleteTarget(null);
    setForget(false);
    setSensitiveConfirmed(false);
    const trigger = triggerRef.current;
    if (trigger instanceof HTMLElement) trigger.focus();
    triggerRef.current = null;
  };

  const refreshLists = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-list'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-settings'] });
  };

  const addMutation = useMutation({
    mutationFn: () => addMemoryItem({
      kind,
      scopeType,
      scopeId: scopeType === 'NOTEBOOK' ? scopeId : undefined,
      predicate: predicate.trim(),
      value: value.trim(),
      validUntil: toInstant(validUntil),
      sensitiveConfirmed: sensitiveConfirmed ? true : undefined,
      operationId: newOperationId()
    }),
    onSuccess: () => {
      setPredicate('');
      setValue('');
      setValidUntil('');
      closeAndRestore();
      refreshLists();
      Message.success('已记下这条记忆');
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'MEMORY_SENSITIVE_CONFIRMATION_REQUIRED') {
        setSensitiveConfirmed(true);
        Message.warning('这段内容看起来含凭据，确认后才写入');
        return;
      }
      Message.error(friendlyMessage(error, '新增记忆失败'));
    }
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteMemoryItem(
      deleteTarget?.memoryId ?? '',
      deleteTarget?.version ?? 0,
      forget,
      newOperationId()
    ),
    onSuccess: () => {
      closeAndRestore();
      refreshLists();
      Message.success(forget ? '这条记忆与其来源证据都已遗忘' : '记忆已删除');
    },
    onError: (error) => Message.error(friendlyMessage(error, '删除失败'))
  });

  const autoExtractMutation = useMutation({
    mutationFn: () => updateMemorySettings(settings?.revision ?? 0, { autoExtract: true, enabled: true }),
    onSuccess: () => {
      refreshLists();
      Message.success('自动记忆已开启');
    },
    onError: (error) => Message.warning(friendlyMessage(error, '设置未保存'))
  });

  if (settingsQuery.isPending) {
    return <section className="panel"><div className="panel-body">正在读取记忆状态…</div></section>;
  }
  if (!settings) {
    return <section className="panel"><div className="panel-body">{friendlyMessage(settingsQuery.error, '记忆状态读取失败')}</div></section>;
  }
  if (!settings.initialized) {
    return (
      <section className="panel">
        <div className="panel-header"><h2>记忆</h2></div>
        <div className="panel-body">
          <Alert type="info" content="还没有记忆库：先在上方创建本地加密库，才能手动添加或查看记忆。" />
        </div>
      </section>
    );
  }
  if (!unlocked) {
    return (
      <section className="panel">
        <div className="panel-header"><h2>记忆</h2></div>
        <div className="panel-body">
          <Alert type="warning" content="记忆库已锁定，解锁后才能看到正文（这不是“没有记忆”）。" />
        </div>
      </section>
    );
  }

  const notebookOptions = (notebooksQuery.data ?? []).map((notebook) => ({ value: notebook.id, label: notebook.title }));

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>记忆</h2>
        <Button type="primary" icon={<Plus size={14} />} onClick={() => openWithFocusRestore(() => setAddVisible(true))}>
          手动添加
        </Button>
      </div>
      <div className="panel-body">
        <MemoryListView
          enabled={unlocked}
          notebooks={notebookOptions}
          onOpen={(item) => openWithFocusRestore(() => setDetail(item))}
          onEdit={(item) => openWithFocusRestore(() => setDetail(item))}
          onDelete={(item) => openWithFocusRestore(() => setDeleteTarget(item))}
          onCreate={() => openWithFocusRestore(() => setAddVisible(true))}
          onEnableAutoExtract={() => autoExtractMutation.mutate()}
        />
      </div>

      <Modal
        title="记忆详情"
        visible={detail != null}
        footer={null}
        onCancel={closeAndRestore}
        unmountOnExit
        style={{ width: 620 }}
      >
        {detail && <MemoryDetail item={detail} onClose={closeAndRestore} onChanged={refreshLists} />}
      </Modal>

      <Modal
        title="手动添加记忆"
        visible={addVisible}
        okText={sensitiveConfirmed ? '仍要写入' : '保存'}
        cancelText="取消"
        confirmLoading={addMutation.isPending}
        onOk={() => addMutation.mutate()}
        onCancel={closeAndRestore}
        style={{ width: 520 }}
      >
        <div className="form-stack">
          {sensitiveConfirmed && (
            <Alert type="warning" content="服务端判定这段内容像凭据。写进记忆后它会参与后续对话，请确认这确实是你要记住的内容。" />
          )}
          <div className="settings-row">
            <Typography.Text bold>类别</Typography.Text>
            <Select value={kind} onChange={(next) => setKind(next as MemoryKind)} options={KIND_OPTIONS} aria-label="记忆类别" style={{ width: 140 }} />
          </div>
          <div className="settings-row">
            <Typography.Text bold>作用域</Typography.Text>
            <Space size="small" wrap>
              <Radio.Group
                type="button"
                size="small"
                value={scopeType}
                onChange={(next) => setScopeType(next as MemoryScopeType)}
                aria-label="记忆作用域"
              >
                <Radio value="GLOBAL">全局</Radio>
                <Radio value="NOTEBOOK">笔记本</Radio>
              </Radio.Group>
              {scopeType === 'NOTEBOOK' && (
                <Select
                  placeholder="选择笔记本"
                  value={scopeId || undefined}
                  onChange={(next) => setScopeId(Number(next))}
                  options={notebookOptions}
                  aria-label="目标笔记本"
                  style={{ width: 180 }}
                />
              )}
            </Space>
          </div>
          <div className="form-stack">
            <Typography.Text bold>键名</Typography.Text>
            <Input
              placeholder="例如 answer.style"
              value={predicate}
              onChange={setPredicate}
              maxLength={120}
              showWordLimit
              aria-label="记忆键名"
            />
          </div>
          <div className="form-stack">
            <Typography.Text bold>内容</Typography.Text>
            <Input.TextArea
              placeholder="要记住的一句话"
              value={value}
              onChange={setValue}
              autoSize={{ minRows: 2, maxRows: 5 }}
              maxLength={1000}
              showWordLimit
              aria-label="记忆内容"
            />
          </div>
          <div className="form-stack">
            <Typography.Text bold>有效期（可选）</Typography.Text>
            <DatePicker value={validUntil} onChange={(text) => setValidUntil(text)} aria-label="记忆有效期" style={{ width: 200 }} />
          </div>
        </div>
      </Modal>

      <Modal
        title="删除这条记忆"
        visible={deleteTarget != null}
        okText="删除"
        okButtonProps={{ status: 'danger' }}
        cancelText="取消"
        confirmLoading={deleteMutation.isPending}
        onOk={() => deleteMutation.mutate()}
        onCancel={closeAndRestore}
      >
        <div className="form-stack">
          <Typography.Text>
            {deleteTarget?.corrupt ? '（这一行读不出正文）' : deleteTarget?.value ?? '（无正文）'}
          </Typography.Text>
          <Checkbox checked={forget} onChange={setForget}>
            同时遗忘来源：推进全局遗忘边界，之后的整理不得再引用更早内容
          </Checkbox>
        </div>
      </Modal>
    </section>
  );
}
