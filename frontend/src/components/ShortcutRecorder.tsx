import { useEffect, useRef } from 'react';
import { Message } from '@arco-design/web-react';
import { Plus, X } from 'lucide-react';
import {
  acceleratorFromEvent,
  acceleratorParts,
  describeAccelerator,
  isModifierKey,
  keyLabel,
  setShortcutRecording,
  type ShortcutRule
} from '../lib/shortcuts';

/**
 * 单个动作的快捷键编辑器：已绑定的组合键逐个显示为可移除的 chip，
 * 末尾的「+」进入录制态捕获下一组按键。
 * Esc 取消；Backspace / Delete 清空该动作全部绑定；单独的修饰键继续等待；
 * 不符合作用域规则的按键（如全局动作录单个字母）提示后退出。
 * 录制态由父组件持有（同一时刻只允许一个录制器），
 * 期间置 shortcuts 模块的 recording 标志，让全局快捷键监听让路。
 */
interface ShortcutRecorderProps {
  /** 当前绑定；[] = 未绑定。 */
  values: readonly string[];
  /** 本动作的单键规则（由作用域与 mainKeys 决定）。 */
  rule: ShortcutRule;
  recording: boolean;
  onStart: () => void;
  onStop: () => void;
  onAdd: (accelerator: string) => void;
  onRemove: (accelerator: string) => void;
  onClear: () => void;
}

/** 把组合键拆成 kbd chips，如 Ctrl+ArrowLeft → [Ctrl][←]。 */
export function ShortcutKeys({ accelerator }: { accelerator: string }): JSX.Element {
  return (
    <>
      {acceleratorParts(accelerator).map((part, index) => (
        <kbd key={`${part}-${index}`} className="shortcut-key">{part}</kbd>
      ))}
    </>
  );
}

function rejectionMessage(rule: ShortcutRule): string {
  if (rule.mainKeys) return `此项只能使用 ${rule.mainKeys.map(keyLabel).join(' / ')}（可加 Ctrl / Alt / Shift）`;
  if (!rule.plainKeys) return '组合键需包含 Ctrl 或 Alt，或直接使用 F1–F12';
  return '不支持该按键';
}

export function ShortcutRecorder({ values, rule, recording, onStart, onStop, onAdd, onRemove, onClear }: ShortcutRecorderProps): JSX.Element {
  // 回调与规则走 ref：录制期间父组件重渲染不重启监听
  const onAddRef = useRef(onAdd);
  onAddRef.current = onAdd;
  const onClearRef = useRef(onClear);
  onClearRef.current = onClear;
  const onStopRef = useRef(onStop);
  onStopRef.current = onStop;
  const ruleRef = useRef(rule);
  ruleRef.current = rule;

  useEffect(() => {
    if (!recording) return;
    setShortcutRecording(true);
    const onKey = (event: KeyboardEvent): void => {
      // 录制期间吞掉所有按键，避免全局快捷键与页面快捷键被同时触发
      event.preventDefault();
      event.stopImmediatePropagation();
      if (event.key === 'Escape') {
        onStopRef.current();
        return;
      }
      if (event.key === 'Backspace' || event.key === 'Delete') {
        onClearRef.current();
        onStopRef.current();
        return;
      }
      if (isModifierKey(event.key)) return; // 组合键未按完，继续等待
      const combo = acceleratorFromEvent(event, ruleRef.current);
      if (!combo) {
        Message.warning(rejectionMessage(ruleRef.current));
        onStopRef.current();
        return;
      }
      onAddRef.current(combo);
      onStopRef.current();
    };
    window.addEventListener('keydown', onKey, true);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      setShortcutRecording(false);
    };
  }, [recording]);

  return (
    <div className="shortcut-editor">
      {values.map((accelerator) => (
        <span key={accelerator} className="shortcut-binding">
          <ShortcutKeys accelerator={accelerator} />
          <button
            type="button"
            className="shortcut-binding-remove"
            aria-label={`移除 ${describeAccelerator(accelerator)}`}
            title="移除"
            onClick={() => onRemove(accelerator)}
          >
            <X size={12} />
          </button>
        </span>
      ))}
      <button
        type="button"
        className={`shortcut-recorder${recording ? ' is-recording' : ''}`}
        aria-label={recording ? '录制中' : values.length ? '录入新按键' : '录入按键'}
        title={recording ? 'Esc 取消 · Backspace 清空' : values.length ? '录入新按键' : '点击设置'}
        onClick={() => { if (!recording) onStart(); }}
      >
        {recording ? '按下按键…' : values.length ? <Plus size={14} /> : <span className="shortcut-key shortcut-key--empty">未设置</span>}
      </button>
    </div>
  );
}
