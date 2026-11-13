import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Empty, Input, Select, Space, Spin, Tag, Typography } from '@arco-design/web-react';
import { friendlyMessage } from '../../lib/errors';
import { searchMemoryItems } from '../../lib/aiMemoryApi';
import {
  memoryDisplayTitle,
  memoryKindLabel,
  memoryScopeLabel,
  memoryStatusLabel,
  memoryStatusTone,
  memoryValidUntilLabel
} from '../../lib/memoryLabels';
import type { MemoryItem, MemoryScopeType, MemoryStatus } from '../../lib/aiMemoryTypes';
import { pageContextRows, usePageContextSection } from '../PageContextProvider';

/**
 * 记忆列表（§12）：一层平铺展示，状态与作用域只做筛选，不另开审阅区。
 * 点整行进详情做管理；增删改的确认弹窗与请求都由 MemoryManager 掌握。
 *
 * <p>缓存键带齐 status/scope/scopeId/query/cursor，换筛选不会混进上一页结果。
 * 库锁着时由调用方 `enabled=false` 直接不请求：423 的含义是"解锁后才能看"，
 * 不是"没有记忆"，两者必须在界面上分开。</p>
 */

export const MEMORY_PAGE_SIZE = 20;

const STATUS_OPTIONS: Array<{ value: MemoryStatus | 'ALL'; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'ACTIVE', label: '生效' },
  { value: 'DISABLED', label: '停用' },
  { value: 'SUPERSEDED', label: '已被替换' },
  { value: 'EXPIRED', label: '已过期' }
];

const SCOPE_OPTIONS: Array<{ value: 'ALL' | MemoryScopeType; label: string }> = [
  { value: 'ALL', label: '全部作用域' },
  { value: 'GLOBAL', label: '全局' },
  { value: 'NOTEBOOK', label: '当前笔记本' }
];

export interface MemoryListViewProps {
  /** 笔记本候选：选"当前笔记本"筛选时用，由调用方给（列表自己存选中的那一本）。 */
  notebooks: Array<{ value: number; label: string }>;
  /** 锁定或还没建库时不请求：后端会 423，界面要说"解锁后才能看"，不能说"没有记忆"。 */
  enabled: boolean;
  onOpen: (item: MemoryItem) => void;
  /** 首次空态里的"手动添加"入口。 */
  onCreate: () => void;
}

export function MemoryListView(props: MemoryListViewProps): JSX.Element {
  const [status, setStatus] = useState<MemoryStatus | 'ALL'>('ALL');
  const [scope, setScope] = useState<'ALL' | MemoryScopeType>('ALL');
  const [notebookId, setNotebookId] = useState<number>(0);
  const [draftQuery, setDraftQuery] = useState('');
  const [query, setQuery] = useState('');
  // 后端只有向前 keyset 游标，"上一页"靠本地这份已翻过的游标栈，不假装能反向分页。
  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const cursor = cursorStack[cursorStack.length - 1];
  const scopeId = scope === 'NOTEBOOK' ? notebookId : 0;
  const notebookMissing = scope === 'NOTEBOOK' && notebookId <= 0;

  const listQuery = useQuery({
    queryKey: ['ai-memory-list', { status, scope, scopeId, query, cursor: cursor ?? '' }],
    queryFn: () => searchMemoryItems({
      status,
      scopeType: scope === 'ALL' ? undefined : scope,
      scopeId: scope === 'NOTEBOOK' ? notebookId : undefined,
      query: query || undefined,
      limit: MEMORY_PAGE_SIZE,
      cursor
    }),
    enabled: props.enabled && !notebookMissing
  });

  const page = listQuery.data;
  // 此处只提供管理筛选与数量，不借设置页面快照绕过记忆工具的读取策略。
  usePageContextSection('memory-list', pageContextRows('记忆列表', [
    ['状态筛选', STATUS_OPTIONS.find(option => option.value === status)?.label],
    ['作用域筛选', SCOPE_OPTIONS.find(option => option.value === scope)?.label],
    ['笔记本', scope === 'NOTEBOOK' ? props.notebooks.find(notebook => notebook.value === notebookId)?.label ?? '尚未选择' : undefined],
    ['当前页', cursorStack.length + 1],
    ['展示数量', props.enabled && !notebookMissing && page ? page.items.length : undefined],
    ['列表状态', !props.enabled ? '记忆库未解锁' : notebookMissing ? '请选择笔记本' : listQuery.isPending ? '正在加载…' : listQuery.isError ? '加载失败' : page?.items.length ? '已加载' : '没有匹配的记忆'],
    ['下一页', page?.hasMore ? '还有更多记忆，可翻页查看' : undefined]
  ]));
  const resetPaging = (): void => setCursorStack([]);
  // "首次空态"只在没有任何记忆的自然视角（全部状态 + 全局 + 无关键词）下成立，
  // 否则切到"已过期"也会弹那两句引导文案。
  const firstRun = !query && scope === 'ALL' && status === 'ALL';

  return (
    <div className="ai-memory-list">
      <Space wrap size="small" className="ai-memory-filters">
        <Select
          value={status}
          onChange={(value) => { setStatus(value as MemoryStatus | 'ALL'); resetPaging(); }}
          options={STATUS_OPTIONS}
          aria-label="按状态筛选"
          style={{ width: 130 }}
        />
        <Select
          value={scope}
          onChange={(value) => { setScope(value as 'ALL' | MemoryScopeType); resetPaging(); }}
          options={SCOPE_OPTIONS}
          aria-label="按作用域筛选"
          style={{ width: 140 }}
        />
        {scope === 'NOTEBOOK' && (
          <Select
            placeholder="选择笔记本"
            value={notebookId || undefined}
            onChange={(value) => { setNotebookId(Number(value)); resetPaging(); }}
            options={props.notebooks}
            aria-label="按笔记本筛选"
            style={{ width: 170 }}
          />
        )}
        <Input.Search
          placeholder="搜索记忆"
          value={draftQuery}
          allowClear
          onChange={setDraftQuery}
          onSearch={(value) => { setQuery(value.trim()); resetPaging(); }}
          aria-label="搜索记忆"
          style={{ width: 240 }}
        />
      </Space>

      {notebookMissing && <Typography.Text type="secondary">再选一本笔记本，才能按它筛选。</Typography.Text>}

      {props.enabled && listQuery.isFetching && (
        // Spin 自身不带 role，包一层 status 活区，屏幕阅读器才会播报"正在读取记忆"
        <span className="ai-memory-spin" role="status" aria-label="正在读取记忆">
          <Spin size={16} />
        </span>
      )}
      {props.enabled && listQuery.isError && (
        // Arco 的 Message 只有 toast 形态，行内常驻报错得用 Alert
        <Alert type="error" content={friendlyMessage(listQuery.error, '记忆列表读取失败')} />
      )}

      {props.enabled && !listQuery.isFetching && page && page.items.length === 0 && (
        firstRun
          ? (
            <div className="ai-memory-empty">
              <Empty description="记住你明确表达的偏好和目标，供以后对话参考。" />
              <Space size="small">
                <Button type="primary" onClick={props.onCreate}>手动添加</Button>
              </Space>
            </div>
          )
          : <Empty className="ai-memory-empty" description={query ? '没有匹配的记忆' : '这个筛选下没有记忆'} />
      )}

      {props.enabled && page && page.items.length > 0 && (
        <ul className="ai-memory-items" role="list">
          {page.items.map((item) => {
            const title = memoryDisplayTitle(item);
            const expiry = memoryValidUntilLabel(item.validUntil);
            const scope = memoryScopeLabel(item.scopeType, item.scopeId, props.notebooks);
            return (
              <li key={item.memoryId} className="ai-memory-item">
                <button
                  type="button"
                  className="ai-memory-item-main"
                  onClick={() => props.onOpen(item)}
                  aria-label={`查看记忆：${title}`}
                >
                  <span className="ai-memory-item-title">{title}</span>
                  <span className="ai-memory-item-sub">
                    <span>{memoryKindLabel(item.kind)}</span>
                    <span aria-hidden="true">·</span>
                    <span>{scope}</span>
                  </span>
                </button>
                <div className="ai-memory-item-side">
                  {expiry && <span className="ai-memory-item-expiry">有效至 {expiry}</span>}
                  <span className={`ai-memory-item-status is-${memoryStatusTone(item.status)}`}>
                    {memoryStatusLabel(item.status)}
                  </span>
                  {item.corrupt && <Tag color="red">密文不可用</Tag>}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      <Space size="small" className="ai-memory-pager">
        <Button
          size="small"
          aria-label="记忆列表上一页"
          disabled={cursorStack.length === 0}
          onClick={() => setCursorStack((stack) => stack.slice(0, -1))}
        >
          上一页
        </Button>
        <Button
          size="small"
          aria-label="记忆列表下一页"
          disabled={!page?.hasMore}
          onClick={() => {
            if (page?.nextCursor) setCursorStack((stack) => [...stack, page.nextCursor as string]);
          }}
        >
          下一页
        </Button>
        {page && <Typography.Text type="secondary">本页 {page.items.length} 条</Typography.Text>}
      </Space>
    </div>
  );
}
