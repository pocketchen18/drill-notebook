import { contextBridge, ipcRenderer } from 'electron';

interface AssistantState { visible: boolean; running: number; pending: number; fixed: boolean }

/** 只暴露固定频道的订阅；返回的函数用于取消订阅。 */
function subscribe<T>(channel: string, listener: (value: T) => void): () => void {
  const handler = (_event: unknown, value: T): void => listener(value);
  ipcRenderer.on(channel, handler);
  return () => { ipcRenderer.removeListener(channel, handler); };
}

contextBridge.exposeInMainWorld('api', {
  backend: {
    getBaseUrl: (): Promise<string> => ipcRenderer.invoke('backend:get-base-url'),
    getAppRoot: (): Promise<string> => ipcRenderer.invoke('app:get-root'),
    getPortableInfo: (): Promise<{ root: string; database: string; portable: boolean }> => ipcRenderer.invoke('app:get-portable-info')
  },
  config: {
    get: (): Promise<{ theme?: 'light' | 'dark' }> => ipcRenderer.invoke('app:get-config'),
    set: (config: { theme: 'light' | 'dark' }): Promise<void> => ipcRenderer.invoke('app:set-config', config)
  },
  dialog: {
    openTextFile: (extensions?: string[]): Promise<{ canceled: boolean; path?: string; content?: string }> => ipcRenderer.invoke('dialog:open-text-file', extensions),
    pickDirectory: (): Promise<string | null> => ipcRenderer.invoke('dialog:pick-directory')
  },
  exportFile: {
    save: (request: { format: 'md' | 'html' | 'pdf'; suggestedName: string; content: string; html: string }): Promise<{ canceled: boolean; path?: string }> => ipcRenderer.invoke('export:save', request)
  },
  file: {
    pickFiles: (filters: { name: string; extensions: string[] }[]): Promise<Array<{ path: string; name: string; size: number }> | null> => ipcRenderer.invoke('dialog:pick-files', filters),
    readFile: (filePath: string): Promise<ArrayBuffer> => ipcRenderer.invoke('file:read-file', filePath)
  },
  shell: {
    openExternal: (url: string): Promise<void> => ipcRenderer.invoke('shell:open-external', url),
    openPath: (targetPath: string): Promise<void> => ipcRenderer.invoke('shell:open-path', targetPath)
  },
  assistant: {
    open: (): Promise<void> => ipcRenderer.invoke('assistant:open'),
    toggle: (): Promise<void> => ipcRenderer.invoke('assistant:toggle'),
    hide: (): Promise<void> => ipcRenderer.invoke('assistant:hide'),
    getState: (): Promise<AssistantState> => ipcRenderer.invoke('assistant:get-state'),
    setFixed: (fixed: boolean): Promise<void> => ipcRenderer.invoke('assistant:set-fixed', fixed),
    attention: (): Promise<void> => ipcRenderer.invoke('assistant:attention'),
    focusMain: (): Promise<void> => ipcRenderer.invoke('window:focus-main'),
    onState: (listener: (state: AssistantState) => void): (() => void) => subscribe('assistant:state', listener),
    onActivated: (listener: () => void): (() => void) => subscribe('assistant:activated', listener)
  },
  bus: {
    publish: (message: unknown): Promise<void> => ipcRenderer.invoke('bus:publish', message),
    subscribe: (listener: (message: unknown) => void): (() => void) => subscribe('bus:message', listener)
  },
  video: {
    fetchTitle: (url: string): Promise<string | null> => ipcRenderer.invoke('video:fetch-title', url)
  }
});
