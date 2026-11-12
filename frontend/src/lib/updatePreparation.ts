import { saveAllNotesBeforeRestart } from './noteAutosave';
import { flushViewState } from './viewState';

const participants = new Map<string, () => void | Promise<void>>();
export function registerUpdateParticipant(name: string, prepare: () => void | Promise<void>): () => void {
  participants.set(name, prepare);
  return () => { if (participants.get(name) === prepare) participants.delete(name); };
}

export async function prepareForRestart(isMutating: () => number): Promise<void> {
  if (isMutating() > 0) throw new Error('仍有操作正在保存，完成后会自动继续更新。');
  await saveAllNotesBeforeRestart();
  for (const prepare of participants.values()) await prepare();
  // Existing editors already implement this guard. A synthetic event checks it without navigating or discarding drafts.
  const guard = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(guard);
  if (guard.defaultPrevented) throw new Error('有未保存的修改，请先保存或完成编辑。更新随后会自动继续。');
  const dialogs = [...document.querySelectorAll('[role="dialog"], .arco-modal')];
  if (dialogs.some(dialog => !dialog.closest('[data-app-update], .app-update-dialog') && !dialog.matches('[data-app-update], .app-update-dialog')
    && dialog.querySelector('input, textarea, [contenteditable="true"]') && dialog.getAttribute('aria-hidden') !== 'true')) {
    throw new Error('请先完成或关闭正在编辑的窗口，更新随后会自动继续。');
  }
  await saveAllNotesBeforeRestart();
  if (isMutating() > 0) throw new Error('正在保存最新修改，完成后会自动继续更新。');
  flushViewState();
}
