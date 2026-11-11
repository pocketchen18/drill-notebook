import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { setAssistantLauncher, useUiStore } from '../stores/uiStore';
import { hideAssistantWindow, onAssistantStatus, openAssistantWindow, publish, subscribe, type AssistantWindowStatus } from '../lib/windowBus';
import { readToolActivities } from '../lib/toolActivity';
import { refreshToolMutation } from '../lib/toolMutationEffects';

/**
 * 主窗口这一侧的助手桥接：把「打开 / 关闭助手」变成对独立窗口的操作，把当前业务页面交给助手，
 * 并执行助手发来的资料定位与刷新。只有用户在助手里点击才会导航，后台任务完成不会切换页面。
 */
export function useAssistantBridge(): AssistantWindowStatus {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AssistantWindowStatus>({ visible: false, running: 0, pending: 0, fixed: false });

  useEffect(() => {
    setAssistantLauncher((request, current) => {
      // 入口按钮与快捷键是「切换」：助手在前台就收起，在后台就唤到前台，由主进程按窗口真实状态判断。
      if (request === 'toggle') {
        if (window.api?.assistant) void window.api.assistant.toggle();
        else if (current) hideAssistantWindow(); else openAssistantWindow();
      } else if (request === 'open') openAssistantWindow();
      else hideAssistantWindow();
      // 桌面应用里以窗口的真实可见性为准；浏览器调试没有状态回报，直接采用请求值。
      return window.api?.assistant ? current : request === 'toggle' ? !current : request === 'open';
    });
    const off = onAssistantStatus((next) => {
      setStatus(next);
      useUiStore.setState({ aiOpen: next.visible });
    });
    return () => { setAssistantLauncher(null); off(); };
  }, []);

  useEffect(() => {
    const send = (): void => publish({ type: 'page-context', context: useUiStore.getState().pageContext });
    const offStore = useUiStore.subscribe((state, previous) => { if (state.pageContext !== previous.pageContext) send(); });
    const offBus = subscribe((message) => {
      if (message.type === 'context-request') send();
      else if (message.type === 'navigate') {
        if (typeof message.route === 'string' && message.route.startsWith('/') && !message.route.startsWith('//')) navigate(message.route);
      } else if (message.type === 'tool-mutation') {
        // 经过同一套白名单校验后再刷新目录；被删笔记的本地草稿在这里丢弃。
        for (const row of readToolActivities([message.row])) refreshToolMutation(queryClient, row);
      } else if (message.type === 'invalidate' && Array.isArray(message.keys)) {
        for (const queryKey of message.keys) if (Array.isArray(queryKey)) void queryClient.invalidateQueries({ queryKey });
      }
    });
    send();
    return () => { offStore(); offBus(); };
  }, [navigate, queryClient]);

  return status;
}
