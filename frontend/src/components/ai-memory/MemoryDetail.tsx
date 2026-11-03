import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, DatePicker, Input, Message, Space, Tag, Typography } from '@arco-design/web-react';
import { ApiError } from '../../lib/api';
import { friendlyMessage } from '../../lib/errors';
import { editMemoryItem, getMemoryItem } from '../../lib/aiMemoryApi';
import { memoryKindLabel, memoryStatusLabel, memoryStatusTone, memoryValidUntilLabel } from '../../lib/memoryLabels';
import type { MemoryFactKeys, MemoryItem, MemoryStatus } from '../../lib/aiMemoryTypes';

/**
 * 记忆详情与编辑（§12）：正文、来源 quote、来源会话、有效期、作用域、版本与人工保护标记。
 *
 * <p>列表视图不带来源，所以这里补取一次详情；编辑带的 expectedVersion 用<b>取回的那一版</b>，
 * 不是打开瞬间那一版。409 时不动用户草稿，只把服务端最新 metadata 摊在旁边，
 * 要不要看最新正文由用户显式点"取回"——自动覆盖等于吃掉用户刚写的东西。取回之后把它当成
 * 新的 CAS 基线并说明"保存会覆盖这一版"，否则用户无论怎么改都只会一直撞 409。</p>
 */

const EDITABLE_STATUSES: MemoryStatus[] = ['ACTIVE', 'DISABLED'];

export interface MemoryDetailProps {
  /** 列表里的行视图；本组件按 memoryId 补取带来源的详情。 */
  item: MemoryItem;
  onClose: () => void;
  onChanged: () => void;
  onDelete: (item: MemoryItem) => void;
}

function Meta(props: { label: string }): JSX.Element {
  return <Typography.Text type="secondary">{props.label}</Typography.Text>;
}

export function MemoryDetail(props: MemoryDetailProps): JSX.Element {
  const queryClient = useQueryClient();
  const detailQuery = useQuery({
    queryKey: ['ai-memory-item', props.item.memoryId],
    queryFn: () => getMemoryItem(props.item.memoryId)
  });

  const [value, setValue] = useState(props.item.value ?? '');
  const [predicate, setPredicate] = useState(props.item.predicate ?? '');
  const [validUntil, setValidUntil] = useState(props.item.validUntil ?? '');
  const [status, setStatus] = useState<MemoryStatus>(props.item.status);
  const [conflict, setConflict] = useState<MemoryFactKeys | null>(null);
  const [latest, setLatest] = useState<MemoryItem | null>(null);
  /** 取回最新正文后把它作为新的 CAS 基线，否则用户改完再保存只会一直 409。 */
  const [adopted, setAdopted] = useState<MemoryItem | null>(null);

  const row = adopted ?? detailQuery.data ?? props.item;
  const corrupt = row.corrupt === true;
  const statusFrozen = !EDITABLE_STATUSES.includes(row.status);
  const sources = row.sources ?? [];
  const conflictStatusLabel = conflict?.status ? memoryStatusLabel(conflict.status) : '未知状态';

  const valueChanged = value !== (row.value ?? '') && value.trim().length > 0;
  const predicateChanged = predicate !== (row.predicate ?? '') && predicate.trim().length > 0;
  const validUntilChanged = validUntil !== (row.validUntil ?? '');
  const statusChanged = !statusFrozen && status !== row.status;
  // 服务端对没有内容的 PATCH 直接 400，按钮不能让人点了才知道没改动
  const dirty = valueChanged || predicateChanged || validUntilChanged || statusChanged;

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-list'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-status'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-memory-item', props.item.memoryId] });
  };

  const saveMutation = useMutation({
    mutationFn: async () => {
      let version = row.version;
      let result;
      if (valueChanged || predicateChanged || validUntilChanged) {
        result = await editMemoryItem(props.item.memoryId, {
          expectedVersion: version,
          value: valueChanged ? value : undefined,
          predicate: predicateChanged ? predicate : undefined,
          validUntil: validUntilChanged ? validUntil : undefined
        });
        version = result.version;
      }
      if (statusChanged) {
        result = await editMemoryItem(props.item.memoryId, { expectedVersion: version, status });
      }
      return result;
    },
    onSuccess: () => {
      setConflict(null);
      setAdopted(null);
      refresh();
      props.onChanged();
      Message.success('记忆已保存');
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'MEMORY_VERSION_CONFLICT') {
        setConflict((error.details ?? { version: 0 }) as MemoryFactKeys);
        Message.warning('这条记忆已被改过，你的草稿保留着');
        return;
      }
      Message.error(friendlyMessage(error, '保存失败'));
    }
  });

  const fetchLatestMutation = useMutation({
    mutationFn: () => getMemoryItem(props.item.memoryId),
    onSuccess: (fresh) => {
      setLatest(fresh);
      setAdopted(fresh);
    },
    onError: (error) => Message.error(friendlyMessage(error, '读取最新正文失败'))
  });

  return (
    <div className="ai-memory-detail form-stack">
      <div className="ai-memory-meta">
        <Space wrap size="small">
          <Tag>{memoryKindLabel(row.kind)}</Tag>
          <Tag color={row.scopeType === 'GLOBAL' ? 'blue' : 'purple'}>
            {row.scopeType === 'GLOBAL' ? '全局' : '笔记本'}
          </Tag>
          <Tag color={row.origin === 'MANUAL' ? 'green' : 'arcblue'}>
            {row.origin === 'MANUAL' ? '人工记录' : '自动整理'}
          </Tag>
          {row.manualProtected && <Tag color="green">人工保护</Tag>}
          {row.pinned && <Tag color="gold">置顶</Tag>}
          <Tag className={`ai-memory-item-status is-${memoryStatusTone(row.status)}`}>
            {memoryStatusLabel(row.status)}
          </Tag>
        </Space>
        <br />
        <Meta label={[
          `记录于 ${row.recordedAt}`,
          row.validUntil ? `有效至 ${memoryValidUntilLabel(row.validUntil)}` : null,
          row.lastConfirmedAt ? `最后确认 ${row.lastConfirmedAt}` : null
        ].filter(Boolean).join(' · ')} />
      </div>

      {corrupt && (
        <Alert type="warning" content="这一行的密文读不出来（库或该行已损坏），界面不猜正文；可以删除或手动重建。" />
      )}
      {detailQuery.isError && (
        <Alert type="warning" content={friendlyMessage(detailQuery.error, '详情读取失败，只能按列表里的快照编辑')} />
      )}

      <div className="form-stack">
        <Typography.Text bold>内容</Typography.Text>
        <Input.TextArea
          placeholder="记忆正文"
          value={value}
          onChange={setValue}
          autoSize={{ minRows: 2, maxRows: 6 }}
          maxLength={1000}
          showWordLimit
          aria-label="记忆正文"
          disabled={corrupt}
        />
        <Typography.Text bold>名称（可选）</Typography.Text>
        <Input
          placeholder="例如 回答风格"
          value={predicate}
          onChange={setPredicate}
          maxLength={120}
          showWordLimit
          aria-label="记忆名称"
          disabled={corrupt}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          只用于区分同类记忆，方便你自己认；留空表示不改名。
        </Typography.Text>
        <Typography.Text bold>有效期（可选）</Typography.Text>
        <DatePicker
          value={validUntil || undefined}
          onChange={(dateString) => setValidUntil(dateString
            ? new Date(`${dateString}T23:59:59`).toISOString()
            : '')}
          format="YYYY-MM-DD"
          allowClear
          style={{ width: '100%' }}
          placeholder="留空表示长期有效"
          aria-label="有效期"
          disabled={corrupt}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          留空表示长期有效；到期后会自动变成“已过期”。
        </Typography.Text>
        {statusFrozen
          ? <Meta label={`状态「${memoryStatusLabel(row.status)}」已由服务端冻结，只能作为新版本替换`} />
          : (
            <>
              <Typography.Text bold>状态</Typography.Text>
              <StatusToggle value={status} onChange={setStatus} />
            </>
          )}
      </div>

      {conflict && (
        <Alert
          type="warning"
          content={(
            <Space direction="vertical" size={4}>
              <Typography.Text bold>
                服务端已有更新（{conflictStatusLabel}），你的草稿基于旧内容
              </Typography.Text>
              <Meta label="冲突详情只带元信息，正文不会随错误返回；点下面的按钮才取回最新值。" />
              <Button size="small" loading={fetchLatestMutation.isPending} onClick={() => fetchLatestMutation.mutate()}>
                取回最新正文
              </Button>
              {latest && (
                <>
                  <Typography.Text>最新值：{latest.value ?? '（读不出正文）'}</Typography.Text>
                  <Meta label="已改用最新内容作为基线，保存会以你的草稿覆盖当前内容。" />
                </>
              )}
            </Space>
          )}
        />
      )}

      <div className="form-stack">
        <Typography.Text bold>来源（{sources.length}）</Typography.Text>
        {sources.length === 0 && (
          <Meta label={row.origin === 'MANUAL' ? '人工添加的记忆没有聊天原话。' : '还没有可显示来源，或来源已被清理。'} />
        )}
        <ul className="ai-memory-sources" role="list">
          {sources.map((source) => (
            <li key={source.evidenceId}>
              <Typography.Text>
                {source.corrupt || source.quote == null ? '（原话密文不可用）' : source.quote}
              </Typography.Text>
              <br />
              <Meta label={`会话：${source.sessionTitle ?? '未知会话'}`} />
            </li>
          ))}
        </ul>
      </div>

      <Space size="small">
        <Button type="primary" loading={saveMutation.isPending} disabled={corrupt || !dirty} onClick={() => saveMutation.mutate()}>
          保存
        </Button>
        <Button status="danger" onClick={() => props.onDelete(row)}>删除</Button>
        <Button onClick={props.onClose}>关闭</Button>
      </Space>
    </div>
  );
}

/** 状态只允许在生效与停用之间切换，别的值由服务端判定，界面不提供选择。 */
function StatusToggle(props: { value: MemoryStatus; onChange: (status: MemoryStatus) => void }): JSX.Element {
  return (
    <Space size="small" className="ai-memory-status-radio">
      {EDITABLE_STATUSES.map((option) => (
        <Button
          key={option}
          size="small"
          type={props.value === option ? 'primary' : 'secondary'}
          onClick={() => props.onChange(option)}
          aria-pressed={props.value === option}
        >
          {option === 'ACTIVE' ? '启用' : '停用'}
        </Button>
      ))}
    </Space>
  );
}
