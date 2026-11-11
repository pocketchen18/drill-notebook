interface AssistantWindowState { visible: boolean; running: number; pending: number; fixed: boolean }

interface ElectronApi {
  backend: {
    getBaseUrl: () => Promise<string>;
    getAppRoot: () => Promise<string>;
    getPortableInfo: () => Promise<{ root: string; database: string; portable: boolean }>;
  };
  config: {
    get: () => Promise<{ theme?: 'light' | 'dark' }>;
    set: (config: { theme: 'light' | 'dark' }) => Promise<void>;
  };
  dialog: {
    openTextFile: (extensions?: string[]) => Promise<{ canceled: boolean; path?: string; content?: string }>;
    pickDirectory: () => Promise<string | null>;
  };
  exportFile: {
    save: (request: { format: 'md' | 'html' | 'pdf'; suggestedName: string; content: string; html: string }) => Promise<{ canceled: boolean; path?: string }>;
  };
  file: {
    pickFiles: (filters: { name: string; extensions: string[] }[]) => Promise<Array<{ path: string; name: string; size: number }> | null>;
    readFile: (filePath: string) => Promise<ArrayBuffer>;
  };
  shell: {
    openExternal: (url: string) => Promise<void>;
    openPath: (targetPath: string) => Promise<void>;
  };
  /** 独立 AI 助手窗口；旧版预加载脚本或浏览器调试环境下不存在。 */
  assistant?: {
    open: () => Promise<void>;
    /** 助手在前台则收起，否则唤到前台。 */
    toggle: () => Promise<void>;
    hide: () => Promise<void>;
    getState: () => Promise<AssistantWindowState>;
    /** 「固定」：让助手窗口保持在其他窗口之上，便于一边看回答一边记笔记。 */
    setFixed: (fixed: boolean) => Promise<void>;
    attention: () => Promise<void>;
    focusMain: () => Promise<void>;
    onState: (listener: (state: AssistantWindowState) => void) => () => void;
    onActivated: (listener: () => void) => () => void;
  };
  bus?: {
    publish: (message: unknown) => Promise<void>;
    subscribe: (listener: (message: unknown) => void) => () => void;
  };
  video: {
    fetchTitle: (url: string) => Promise<string | null>;
  };
}

interface Window {
  api: ElectronApi;
}
