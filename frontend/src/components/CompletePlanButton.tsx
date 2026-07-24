import { useState } from 'react';
import { Button, Message } from '@arco-design/web-react';
import { CheckCircle2 } from 'lucide-react';
import { friendlyMessage } from '../lib/errors';
import { completeStudy } from '../lib/study';

export type CompletePlanButtonProps = {
  resourceType?: string;
  resourceId?: number;
  planItemId?: number;
  /** Optional SM-2 quality (0–5). Plan-only when omitted. */
  quality?: number;
  source?: string;
};

/**
 * Shown when arriving from a study-plan deep link (`?planItemId=`).
 * Marks the item done via /api/study/complete (plan + optional SRS).
 */
export function CompletePlanButton({
  resourceType,
  resourceId,
  planItemId,
  quality,
  source = 'calendar'
}: CompletePlanButtonProps): JSX.Element | null {
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  if (!planItemId || !resourceType || !resourceId || done) return null;

  const complete = async (): Promise<void> => {
    setLoading(true);
    try {
      await completeStudy({
        resourceType,
        resourceId,
        planItemId,
        quality,
        source
      });
      Message.success('已完成该计划项');
      setDone(true);
    } catch (error) {
      Message.error(friendlyMessage(error, '标记完成失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Button
      type="outline"
      status="success"
      icon={<CheckCircle2 size={16} />}
      loading={loading}
      onClick={() => void complete()}
    >
      完成此项计划
    </Button>
  );
}
