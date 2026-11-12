import { afterEach, describe, expect, it, vi } from 'vitest';
import { prepareForRestart, registerUpdateParticipant } from './updatePreparation';

describe('更新前保存协调', () => {
  afterEach(() => { document.body.innerHTML = ''; });
  it('等待每个参与者完成，业务修改或脏编辑阻止退出', async () => {
    const save = vi.fn().mockResolvedValue(undefined); const stop = registerUpdateParticipant('test', save);
    try {
      await prepareForRestart(() => 0); expect(save).toHaveBeenCalledTimes(1);
      await expect(prepareForRestart(() => 1)).rejects.toThrow('正在保存');
      const guard = (event: Event): void => event.preventDefault();
      window.addEventListener('beforeunload', guard);
      try { await expect(prepareForRestart(() => 0)).rejects.toThrow('未保存'); }
      finally { window.removeEventListener('beforeunload', guard); }
    } finally { stop(); }
  });
  it('更新范围输入不算业务编辑弹窗，实际业务编辑弹窗会阻止退出', async () => {
    document.body.innerHTML = '<div role="dialog" class="app-update-dialog"><input /></div>';
    await expect(prepareForRestart(() => 0)).resolves.toBeUndefined();
    document.body.innerHTML += '<div role="dialog"><textarea>正在编辑</textarea></div>';
    await expect(prepareForRestart(() => 0)).rejects.toThrow('正在编辑的窗口');
  });
  it('参与者保存失败不会被吞掉', async () => {
    const stop = registerUpdateParticipant('test-failure', () => { throw new Error('草稿保存失败'); });
    try { await expect(prepareForRestart(() => 0)).rejects.toThrow('草稿保存失败'); } finally { stop(); }
  });
});
