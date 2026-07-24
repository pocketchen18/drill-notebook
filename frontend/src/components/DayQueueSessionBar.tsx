import { Button, Message, Space, Typography } from '@arco-design/web-react';
import { Calendar, LogOut } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import {
  advanceDayQueueSession,
  clearDayQueueSession,
  dayQueueProgressLabel,
  readDayQueueSession
} from '../lib/dayQueueSession';

const { Text } = Typography;

/**
 * Shown during a day-queue run. Exit returns to calendar; finishCurrentStep advances to next type.
 */
export function DayQueueSessionBar(): JSX.Element | null {
  const navigate = useNavigate();
  const session = readDayQueueSession();
  if (!session) return null;

  const exit = (): void => {
    clearDayQueueSession();
    Message.info('已退出今日任务，可稍后在日历继续');
    navigate(`/calendar?date=${session.date}`);
  };

  return (
    <div className="day-queue-session-bar">
      <Text style={{ flex: 1, minWidth: 0 }}>{dayQueueProgressLabel(session)}</Text>
      <Space size={8}>
        <Button size="small" icon={<Calendar size={14} />} onClick={exit}>
          退出回日历
        </Button>
      </Space>
    </div>
  );
}

/** Call when a quiz/knowledge segment ends; navigates to next step or calendar. */
export function finishDayQueueStep(navigate: (path: string) => void): boolean {
  const session = readDayQueueSession();
  if (!session) return false;
  const nextPath = advanceDayQueueSession();
  if (nextPath) {
    Message.success('本段完成，进入下一段今日任务');
    navigate(nextPath);
    return true;
  }
  Message.success('今日任务全部完成');
  navigate(`/calendar?date=${session.date}`);
  return true;
}

export function isDayQueueMode(searchParams: URLSearchParams): boolean {
  return searchParams.get('dayQueue') === '1';
}
