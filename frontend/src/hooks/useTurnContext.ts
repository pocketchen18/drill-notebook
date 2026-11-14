import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError } from '../lib/api';
import { previewTurnSources, readTurnContext } from '../lib/aiContextApi';
import type { SourcePreview, TurnContextRead } from '../lib/aiContextApi';

/**
 * 检查器与徽标共用的只读数据面（contracts §4.5）：
 * 账本只在面板打开时读已保存的审计；运行中刷新数值，预览只由用户逐条点出来。
 * 这里没有任何路径会新建计划、触发检索/维护/重放或发一轮新聊天。
 */
export function useTurnContextAudit(sessionId: number | undefined, requestId: string | null | undefined,
                                    enabled: boolean) {
  return useQuery<TurnContextRead>({
    // key 带会话与轮次身份：切会话不会把上一轮的账本串过来（执行方案 §12）
    queryKey: ['ai-turn-context', sessionId ?? 'none', requestId ?? 'none'],
    queryFn: () => readTurnContext(requestId as string),
    enabled: enabled && Boolean(requestId),
    refetchInterval: query => query.state.data?.status === 'RUNNING' ? 1500 : false,
    retry: false
  });
}

export interface SourcePreviewState {
  rows: Record<string, SourcePreview>;
  pendingKey: string | null;
  error: string | null;
  preview: (sourceKey: string) => void;
}

/**
 * 逐条来源预览：结果只活在组件里，不进 React Query 缓存。
 * 换轮次或关闭面板即清空——锁定、删除、改版之后都不能再显示上一轮拿到的正文片段。
 */
export function useSourcePreview(requestId: string | null | undefined): SourcePreviewState {
  const [rows, setRows] = useState<Record<string, SourcePreview>>({});
  const [pendingKey, setPendingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const epoch = useRef(0);

  useEffect(() => {
    epoch.current++;
    setRows({});
    setPendingKey(null);
    setError(null);
    return () => { epoch.current++; };
  }, [requestId]);

  const preview = useCallback((sourceKey: string) => {
    if (!requestId) return;
    const run = ++epoch.current;
    setPendingKey(sourceKey);
    setError(null);
    previewTurnSources(requestId, [sourceKey])
      .then((result) => {
        if (run !== epoch.current) return;
        const row = result.find((item) => item.sourceKey === sourceKey);
        if (!row) {
          setRows(prev => { const next = { ...prev }; delete next[sourceKey]; return next; });
          setError('这一条来源没有返回结果');
          return;
        }
        setRows((prev) => ({ ...prev, [sourceKey]: row }));
      })
      .catch((reason: unknown) => {
        if (run !== epoch.current) return;
        // 读不到就撤下旧的对照结果：留在原地会冒充"现在还是这样"
        setRows((prev) => {
          const next = { ...prev };
          delete next[sourceKey];
          return next;
        });
        setError(reason instanceof ApiError ? reason.message : '对照来源失败，请稍后再试');
      })
      .finally(() => { if (run === epoch.current) setPendingKey(null); });
  }, [requestId]);

  return { rows, pendingKey, error, preview };
}
