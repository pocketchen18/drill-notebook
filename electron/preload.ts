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
    openTextFile: (): Promise<{ canceled: boolean; path?: string; content?: string }> => ipcRenderer.invoke('dialog:open-text-file')
  },
  exportFile: {
    save: (request: { format: 'md' | 'html' | 'pdf'; suggestedName: string; content: string; html: string }): Promise<{ canceled: boolean; path?: string }> => ipcRenderer.invoke('export:save', request)
  }
});
