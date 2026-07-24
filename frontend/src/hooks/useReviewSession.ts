import { useState, useCallback } from 'react';
import { useReviewStore } from '../stores/reviewStore';
import { getDueItems, submitReview } from '../lib/review';

export function useReviewSession() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const store = useReviewStore();

  const startReview = useCallback(async (params?: {
    type?: string;
    configId?: number;
    newLimit?: number;
    reviewLimit?: number;
    priority?: string;
  }) => {
    setLoading(true);
    setError(null);
    try {
      const items = await getDueItems(params);
      store.startSession(items);
      return items;
    } catch (e) {
      const msg = e instanceof Error ? e.message : '加载复习内容失败';
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, [store]);

  const submit = useCallback(async (scheduleId: number, quality: number, responseTime?: number) => {
    setError(null);
    try {
      const result = await submitReview(scheduleId, quality, responseTime, 'review');
      store.markAnswered(scheduleId, {
        quality,
        responseTime,
        result: {
          ef: result.ef,
          interval: result.interval,
          repetitions: result.repetitions,
          nextReview: result.nextReview,
          status: result.status,
        },
      });
      return result;
    } catch (e) {
      const msg = e instanceof Error ? e.message : '提交复习结果失败';
      setError(msg);
      throw e;
    }
  }, [store]);

  return {
    ...store,
    loading,
    error,
    startReview,
    submit,
  };
}
