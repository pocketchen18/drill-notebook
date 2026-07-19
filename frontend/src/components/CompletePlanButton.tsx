import { useState } from 'react';
import { Button, Message } from '@arco-design/web-react';
import { CheckCircle2 } from 'lucide-react';
import { put } from '../lib/api';
import { friendlyMessage } from '../lib/errors';

/**
 * Shown when arriving from a study-plan deep link (`?planItemId=`).
 * Marks the item done via PUT; does not auto-complete on quiz answers.
 */
export function CompletePlanButton({ planItemId }: { planItemId?: number }): JSX.Element | null {
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  if (!planItemId || done) return null;

  const complete = async (): Promise<void> => {
    setLoading(true);
    try {
      await put(`/api/study-plans/items/${planItemId}`, { status: 'done' });
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
