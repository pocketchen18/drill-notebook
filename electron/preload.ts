import { contextBridge, ipcRenderer } from 'electron';

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
    openTextFile: (extensions?: string[]): Promise<{ canceled: boolean; path?: string; content?: string }> => ipcRenderer.invoke('dialog:open-text-file', extensions)
  },
  exportFile: {
    save: (request: { format: 'md' | 'html' | 'pdf'; suggestedName: string; content: string; html: string }): Promise<{ canceled: boolean; path?: string }> => ipcRenderer.invoke('export:save', request)
  },
  file: {
    pickFiles: (filters: { name: string; extensions: string[] }[]): Promise<Array<{ path: string; name: string; size: number }> | null> => ipcRenderer.invoke('dialog:pick-files', filters),
    readFile: (filePath: string): Promise<ArrayBuffer> => ipcRenderer.invoke('file:read-file', filePath)
  },
  shell: {
    openExternal: (url: string): Promise<void> => ipcRenderer.invoke('shell:open-external', url)
  },
  video: {
    fetchTitle: (url: string): Promise<string | null> => ipcRenderer.invoke('video:fetch-title', url)
  },
  window: {
    setFullScreen: (flag: boolean): Promise<void> => ipcRenderer.invoke('window:set-full-screen', flag),
    isFullScreen: (): Promise<boolean> => ipcRenderer.invoke('window:is-full-screen')
  }
});
