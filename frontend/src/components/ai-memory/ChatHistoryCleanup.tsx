import { useMemo, useRef, useState } from 'react';
import { Button, Checkbox, InputNumber, Message, Modal, Select, Typography } from '@arco-design/web-react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../../lib/api';
import {
  cleanupBatchLines,
  cleanupErrorText,
  cleanupExpiryLine,
  cleanupImpactLine,
  cleanupLegacyLine,
  cleanupScopeLine,
  formatStoredBytes
} from '../../lib/aiTurnText';
import {
  executeCleanup,
  previewCleanup,
  storageQueryKey,
  type ChatCleanupPreview,
  type ChatCleanupResult
} from '../../lib/aiStorageApi';
import { completeLoadedTurns, keepBoundary } from '../../lib/chatHistoryTurns';
import { historyQueryKey, type ChatHistoryMessage } from '../../hooks/useChatHistoryPages';
import { generateUuidV7 } from '../../lib/uuidV7';

/** 契约 §3：一次预览最多固定 200 个完整轮次。 */
const MAX_CLEANUP_TURNS = 200;
const KEEP_OPTIONS = [10, 20, 50, 100];
/** 自定义选项的哨兵值（Select 的 value 必须是数字，`0 轮`本身不合法）。 */
const CUSTOM = 0;

interface Props {
  sessionId: number | undefined;
  enabled: boolean;
  messages: ChatHistoryMessage[];
  hasOlder: boolean;
  isLoadingOlder: boolean;
  loadOlder: () => void;
  onExecuted?: (result: ChatCleanupResult) => void;
}

/**
 * 历史清理入口与确认弹窗（contracts §3 的 `/history/cleanup/preview` 与 `/history/cleanup`，
 * 执行方案 §7.4.5/§7.4.6）。
 *
 * <p>三步都摆在明面上：选"保留最近 N 轮" → 看清服务端钉死的范围与受影响记忆 → 勾选"无法恢复"再执行。
 * 边界只从**已加载的完整轮次**里取（见 `lib/chatHistoryTurns`），不够就禁用并说明，绝不猜一个边界；
 * 一份预览被服务端打回（409/410）就作废、只能重新预览，界面不静默重试。</p>
 */
export function ChatHistoryCleanup({
  sessionId,
  enabled,
  messages,
  hasOlder,
  isLoadingOlder,
  loadOlder,
  onExecuted
}: Props) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState<number>(KEEP_OPTIONS[0]);
  const [customKeep, setCustomKeep] = useState<number>(30);
  const [preview, setPreview] = useState<ChatCleanupPreview | undefined>();
  const [previewing, setPreviewing] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [ack, setAck] = useState(false);
  const [errorText, setErrorText] = useState<string | undefined>();
  // 同一份预览的多次提交必须带同一个 operationId（网络失败后重试才不会被当成两次清理）
  const operationIdRef = useRef('');

  const keep = choice === CUSTOM ? customKeep : choice;
  const turns = useMemo(() => completeLoadedTurns(messages, hasOlder), [messages, hasOlder]);
  const boundary = keepBoundary(turns, keep);
  const enough = boundary !== undefined;

  const resetPreview = (): void => {
    setPreview(undefined);
    setAck(false);
    setErrorText(undefined);
  };

  const openDialog = (): void => {
    setChoice(KEEP_OPTIONS[0]);
    setCustomKeep(30);
    resetPreview();
    setOpen(true);
  };

  const chooseKeep = (value: number): void => {
    setChoice(value);
    resetPreview();
  };

  const chooseCustom = (value: number | undefined): void => {
    setCustomKeep(Number(value) || 1);
    resetPreview();
  };

  const runPreview = async (): Promise<void> => {
    if (sessionId === undefined || boundary === undefined) return;
    setPreviewing(true);
    setErrorText(undefined);
    setAck(false);
    try {
      const next = await previewCleanup({
        sessionId,
        beforeOrAtMessageId: boundary,
        maxTurns: MAX_CLEANUP_TURNS
      });
      operationIdRef.current = generateUuidV7(Date.now());
      setPreview(next);
    } catch (error) {
      const apiError = error instanceof ApiError ? error : undefined;
      setErrorText(apiError ? cleanupErrorText(apiError.code, apiError.message) : '预览失败，请稍后再试');
    } finally {
      setPreviewing(false);
    }
  };

  const runCleanup = async (): Promise<void> => {
    if (!preview || !ack || executing) return;
    setExecuting(true);
    setErrorText(undefined);
    try {
      const result = await executeCleanup({
        previewId: preview.previewId,
        expectedHistoryRevision: preview.historyRevision,
        operationId: operationIdRef.current,
        confirm: true
      });
      Message.success(`已清理 ${result.cleanedTurns} 轮，释放 ${formatStoredBytes(result.releasedHistoryBytes)}`);
      setOpen(false);
      setPreview(undefined);
      setAck(false);
      // 账本、历史游标、会话列表都变了：全量重取，不让旧页拼回已删的内容
      void client.invalidateQueries({ queryKey: storageQueryKey });
      if (sessionId !== undefined) void client.resetQueries({ queryKey: historyQueryKey(sessionId) });
      void client.invalidateQueries({ queryKey: ['ai-sessions'] });
      onExecuted?.(result);
    } catch (error) {
      const apiError = error instanceof ApiError ? error : undefined;
      setErrorText(apiError ? cleanupErrorText(apiError.code, apiError.message) : '清理没有成功，请稍后再试');
      // 409/410 都意味着"这份预览不再代表将要删除的内容"：立刻作废，逼用户重新看一眼
      if (apiError && (apiError.status === 409 || apiError.status === 410)) setPreview(undefined);
      setAck(false);
    } finally {
      setExecuting(false);
    }
  };

  const legacyLine = preview ? cleanupLegacyLine(preview) : undefined;
  const batchLines = preview ? cleanupBatchLines(preview) : [];
  const expiryLine = preview ? cleanupExpiryLine(preview.expiresAt) : undefined;

  return (
    <>
      <Button
        size="mini"
        type="text"
        className="ai-cleanup-entry"
        disabled={!enabled || sessionId === undefined}
        onClick={openDialog}
      >
        清理历史
      </Button>
      <Modal
        title="清理历史原文"
        visible={open}
        okText="确认清理"
        okButtonProps={{ status: 'danger', disabled: !preview || !ack }}
        cancelText="取消"
        confirmLoading={executing}
        onOk={() => { void runCleanup(); }}
        onCancel={() => { if (!executing) setOpen(false); }}
        style={{ width: 560 }}
      >
        <div className="form-stack ai-cleanup-body">
          <div className="ai-cleanup-keep">
            <Typography.Text>保留最近</Typography.Text>
            <Select
              size="small"
              value={choice}
              onChange={chooseKeep}
              style={{ width: 110 }}
              aria-label="保留最近多少轮"
              options={[...KEEP_OPTIONS.map((value) => ({ label: `${value} 轮`, value })), { label: '自定义', value: CUSTOM }]}
            />
            {choice === CUSTOM ? (
              // 上限只是挡明显误输：留着 300 轮也合法，"一次最多清 200 轮"管的是执行批次，不是这里
              <InputNumber
                size="small"
                min={1}
                max={9999}
                value={customKeep}
                onChange={chooseCustom}
                style={{ width: 90 }}
                aria-label="自定义保留轮数"
              />
            ) : null}
            <Typography.Text>轮</Typography.Text>
          </div>

          {enough ? null : (
            <div className="ai-cleanup-hint">
              <Typography.Text type="secondary">
                {hasOlder
                  ? `更早的历史还没加载够，至少要看到 ${keep + 1} 轮才能确定边界`
                  : `当前只加载到 ${turns.length} 轮完整历史，没有更早的可以清理`}
              </Typography.Text>
              {hasOlder ? (
                <Button size="mini" type="text" loading={isLoadingOlder} onClick={loadOlder}>
                  加载更多历史
                </Button>
              ) : null}
            </div>
          )}

          <div>
            <Button size="small" loading={previewing} disabled={!enough} onClick={() => { void runPreview(); }}>
              {preview ? '重新预览' : '查看将清理的内容'}
            </Button>
          </div>

          {preview ? (
            <div className="ai-cleanup-facts">
              <Typography.Text bold>{cleanupScopeLine(preview)}</Typography.Text>
              {legacyLine ? <Typography.Text>{legacyLine}</Typography.Text> : null}
              {batchLines.map((line) => (
                <Typography.Text key={line} type="secondary">{line}</Typography.Text>
              ))}
              <Typography.Text>{cleanupImpactLine(preview)}</Typography.Text>
              {expiryLine ? <Typography.Text type="secondary">{expiryLine}</Typography.Text> : null}
            </div>
          ) : null}

          {errorText ? (
            <Typography.Text className="ai-cleanup-error">{errorText}</Typography.Text>
          ) : null}

          <Checkbox checked={ack} disabled={!preview} onChange={setAck}>
            我明白清理后无法恢复
          </Checkbox>
        </div>
      </Modal>
    </>
  );
}
