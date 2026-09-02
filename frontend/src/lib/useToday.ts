import { useEffect, useState } from 'react';
import { todayYmd } from './studyPlan';

/**
 * 实时「今天」：每 60 秒对齐系统日期，跨零点后返回值变化并触发宿主组件重渲染。
 * 用于日历的今天高亮、逾期标记与队列数据刷新。
 */
export function useToday(): string {
  const [today, setToday] = useState(() => todayYmd());
  useEffect(() => {
    const tick = (): void => {
      const now = todayYmd();
      setToday((prev) => (prev === now ? prev : now));
    };
    // 切回前台/窗口聚焦时立即校准一次，避免挂机后等待轮询
    window.addEventListener('focus', tick);
    const timer = window.setInterval(tick, 60_000);
    return () => {
      window.removeEventListener('focus', tick);
      window.clearInterval(timer);
    };
  }, []);
  return today;
}
