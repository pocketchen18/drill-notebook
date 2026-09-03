import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { SHORTCUT_ACTIONS, isShortcutRecording, matchesAny, type ShortcutAction } from '../lib/shortcuts';
import { useUiStore } from '../stores/uiStore';

/** 只有全局作用域的动作由这里处理；页面内动作由各页面自己按 shortcutConfig 匹配。 */
const GLOBAL_ACTIONS = SHORTCUT_ACTIONS.filter((meta) => meta.scope === 'global');

function runShortcutAction(action: ShortcutAction, navigate: (path: string) => void): void {
  if (action === 'toggleAi') useUiStore.getState().toggleAi();
  else if (action === 'toggleTheme') useUiStore.getState().toggleTheme();
  else if (action === 'openSettings') navigate('/settings');
}

/**
 * 全局快捷键监听：按 uiStore.shortcutConfig 匹配组合键并触发动作。
 * 每次 keydown 现取 store，设置页改绑即时生效；录制快捷键期间让路。
 */
export function useGlobalShortcuts(): void {
  const navigate = useNavigate();
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (isShortcutRecording()) return;
      const { shortcutConfig } = useUiStore.getState();
      for (const meta of GLOBAL_ACTIONS) {
        if (matchesAny(event, shortcutConfig[meta.id])) {
          event.preventDefault();
          runShortcutAction(meta.id, navigate);
          return;
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navigate]);
}
