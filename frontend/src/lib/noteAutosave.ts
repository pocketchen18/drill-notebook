/** 笔记本无感自动保存协调器。
 *  草稿永远与 pageId 绑定：切页/切库/切后台都不会把 A 页内容写进 B 页。
 *  400ms 防抖合并连续输入；切走页面不取消计时，旧页草稿照旧落库；
 *  take() 供 pagehide / visibilitychange 做 keepalive 兜底冲刷。 */
export interface AutosavePayload {
  pageId: number;
  content: Record<string, unknown>;
}

export class NoteAutosave {
  private pending = new Map<number, AutosavePayload>();
  private server = new Map<number, string>();
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly send: (payload: AutosavePayload) => Promise<unknown>,
    private readonly delay = 400
  ) {}

  /** 服务端快照到达：记录基线，用于识别编辑器 setContent 重置产生的回声。 */
  noteServer(pageId: number, content: Record<string, unknown>): void {
    this.server.set(pageId, JSON.stringify(content));
  }

  hasPendingFor(pageId: number): boolean {
    return this.pending.has(pageId);
  }

  /** 编辑器内容变化：绑定 pageId 记为待保存；与服务端基线完全相同则视为回声，取消该页待保存。 */
  change(pageId: number, content: Record<string, unknown>): void {
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

  /** 页面/笔记本删除后丢弃其待保存草稿，避免向已删除资源发 PUT。 */
  discard(pageId: number): void {
    this.pending.delete(pageId);
  }

  private schedule(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      const payloads = [...this.pending.values()];
      this.pending.clear();
      for (const payload of payloads) {
        void this.send(payload).then(() => {
          this.server.set(payload.pageId, JSON.stringify(payload.content));
        });
      }
    }, this.delay);
  }
}
