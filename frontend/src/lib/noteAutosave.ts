/** 笔记本无感自动保存协调器。
 *  草稿永远与 pageId 绑定：切页/切库/切后台都不会把 A 页内容写进 B 页。
 *  400ms 防抖合并连续输入；切走页面不取消计时，旧页草稿照旧落库；
 *  flush() 供 pagehide / visibilitychange 做 keepalive 兜底冲刷，确认前保持草稿保护。 */
export interface AutosavePayload {
  pageId: number;
  content: Record<string, unknown>;
}

const activeAutosaves = new Set<NoteAutosave>();

/** Update shutdown waits for confirmed writes, including coordinators whose page has already unmounted. */
export async function saveAllNotesBeforeRestart(): Promise<void> {
  for (const coordinator of [...activeAutosaves]) await coordinator.flushAndWait();
}

export class NoteAutosave {
  private pending = new Map<number, AutosavePayload>();
  private server = new Map<number, string>();
  private saving = new Map<number, Set<AutosavePayload>>();
  private failed = new Set<number>();
  private discarded = new Set<number>();
  private latest = new Map<number, AutosavePayload>();
  private requests = new Set<Promise<unknown>>();
  private inFlight = new Map<number, Promise<unknown>>();
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
    if (this.server.get(pageId) === json && !this.saving.has(pageId)) {
      this.pending.delete(pageId);
      return;
    }
    const payload = { pageId, content };
    this.pending.set(pageId, payload);
    this.latest.set(pageId, payload);
    activeAutosaves.add(this);
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
      activeAutosaves.add(this);
      const saves = this.saving.get(payload.pageId) ?? new Set<AutosavePayload>();
      saves.add(payload);
      this.saving.set(payload.pageId, saves);
      let request: Promise<unknown>;
      try {
        const prior = this.inFlight.get(payload.pageId);
        request = prior ? prior.catch(() => undefined).then(() => this.discarded.has(payload.pageId) ? undefined : send(payload))
          : Promise.resolve(send(payload));
      }
      catch (error) { request = Promise.reject(error); }
      const tracked = request.then(() => {
        if (this.discarded.has(payload.pageId)) return;
        this.server.set(payload.pageId, JSON.stringify(payload.content));
        this.failed.delete(payload.pageId);
      }, () => {
        if (!this.discarded.has(payload.pageId)) {
          this.failed.add(payload.pageId);
          if (this.latest.get(payload.pageId) === payload && !this.pending.has(payload.pageId)) this.pending.set(payload.pageId, payload);
        }
      }).finally(() => {
        saves.delete(payload);
        if (!saves.size) this.saving.delete(payload.pageId);
        this.requests.delete(tracked);
        if (this.inFlight.get(payload.pageId) === tracked) this.inFlight.delete(payload.pageId);
        this.releaseIfIdle();
        this.onSettled();
      });
      this.requests.add(tracked);
      this.inFlight.set(payload.pageId, tracked);
    }
    this.releaseIfIdle();
  }

  async flushAndWait(): Promise<void> {
    do {
      this.flush();
      await Promise.all([...this.requests]);
      if (this.failed.size) throw new Error('笔记尚未保存成功，请检查连接。草稿已保留，保存成功后会继续更新。');
    } while (this.pending.size || this.requests.size);
    this.releaseIfIdle();
  }

  private releaseIfIdle(): void {
    if (!this.pending.size && !this.requests.size && !this.failed.size) activeAutosaves.delete(this);
  }

  /** 页面/笔记本删除后丢弃其待保存草稿，避免向已删除资源发 PUT。 */
  discard(pageId: number): void {
    this.discarded.add(pageId);
    this.pending.delete(pageId);
    this.failed.delete(pageId);
    this.server.delete(pageId);
    this.saving.delete(pageId);
    this.latest.delete(pageId);
    this.releaseIfIdle();
  }

  private schedule(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.flush();
    }, this.delay);
  }
}
