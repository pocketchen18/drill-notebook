/** 笔记本无感自动保存协调器。
 *  草稿永远与 pageId 绑定：切页/切库/切后台都不会把 A 页内容写进 B 页。
 *  400ms 防抖合并连续输入；切走页面不取消计时，旧页草稿照旧落库；
 *  flush() 供 pagehide / visibilitychange 做 keepalive 兜底冲刷，确认前保持草稿保护。 */
export interface AutosavePayload {
  pageId: number;
  content: Record<string, unknown>;
}

export class NoteAutosave {
  private pending = new Map<number, AutosavePayload>();
  private server = new Map<number, string>();
  private saving = new Map<number, Set<AutosavePayload>>();
  private failed = new Set<number>();
  private discarded = new Set<number>();
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly send: (payload: AutosavePayload) => Promise<unknown>,
    private readonly delay = 400,
    private readonly onSettled: () => void = () => undefined
  ) {}

  /** 服务端快照到达：记录基线，用于识别编辑器 setContent 重置产生的回声。 */
  noteServer(pageId: number, content: Record<string, unknown>): void {
    if (this.discarded.has(pageId)) return;
    this.server.set(pageId, JSON.stringify(content));
  }

  hasPendingFor(pageId: number): boolean {
    return this.pending.has(pageId) || !!this.saving.get(pageId)?.size || this.failed.has(pageId);
  }

  /** 编辑器内容变化：绑定 pageId 记为待保存；与服务端基线完全相同则视为回声，取消该页待保存。 */
  change(pageId: number, content: Record<string, unknown>): void {
    if (this.discarded.has(pageId)) return;
    const json = JSON.stringify(content);
    if (this.server.get(pageId) === json) {
      this.pending.delete(pageId);
      return;
    }
    this.pending.set(pageId, { pageId, content });
    this.schedule();
  }

  /** 取出全部待保存草稿并清空（调用方用 keepalive 发送）；无待保存返回空数组。 */
  take(): AutosavePayload[] {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    const payloads = [...this.pending.values()];
    this.pending.clear();
    return payloads;
  }

  /** 立即发送待保存内容，普通保存和 keepalive 都跟踪到结果，避免刷新覆盖在途草稿。 */
  flush(send = this.send): void {
    for (const payload of this.take()) {
      const saves = this.saving.get(payload.pageId) ?? new Set<AutosavePayload>();
      saves.add(payload);
      this.saving.set(payload.pageId, saves);
      let request: Promise<unknown>;
      try { request = Promise.resolve(send(payload)); }
      catch (error) { request = Promise.reject(error); }
      void request.then(() => {
        if (this.discarded.has(payload.pageId)) return;
        this.server.set(payload.pageId, JSON.stringify(payload.content));
        this.failed.delete(payload.pageId);
      }, () => { if (!this.discarded.has(payload.pageId)) this.failed.add(payload.pageId); }).finally(() => {
        saves.delete(payload);
        if (!saves.size) this.saving.delete(payload.pageId);
        this.onSettled();
      });
    }
  }

  /** 页面/笔记本删除后丢弃其待保存草稿，避免向已删除资源发 PUT。 */
  discard(pageId: number): void {
    this.discarded.add(pageId);
    this.pending.delete(pageId);
    this.failed.delete(pageId);
    this.server.delete(pageId);
    this.saving.delete(pageId);
  }

  private schedule(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.flush();
    }, this.delay);
  }
}
