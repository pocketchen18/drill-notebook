import { useEffect } from 'react';
import { useBlocker } from 'react-router-dom';

/** 路由由数据路由器托管，浏览器前进/后退与应用内导航共用同一份离开提示。 */
export function ConnectionNavigationGuard({ dirty, request }: { dirty: () => boolean; request: (go: () => void, stay?: () => void) => void }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty() && currentLocation.pathname !== nextLocation.pathname);
  useEffect(() => {
    if (blocker.state === 'blocked') request(() => blocker.proceed(), () => blocker.reset());
  }, [blocker.state]);
  return null;
}
