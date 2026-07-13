/// <reference types="vite/client" />

interface Window {
  api?: {
    backend: {
      getBaseUrl(): Promise<string>;
      getAppRoot(): Promise<string>;
      getPortableInfo(): Promise<{ root: string; database: string; portable: boolean }>;
    };
    config: {
      get(): Promise<{ theme?: 'light' | 'dark' }>;
      set(config: { theme: 'light' | 'dark' }): Promise<void>;
    };
    dialog: {
      openTextFile(): Promise<{ canceled: boolean; path?: string; content?: string }>;
    };
    exportFile: {
      save(request: { format: 'md' | 'html' | 'pdf'; suggestedName: string; content: string; html: string }): Promise<{ canceled: boolean; path?: string }>;
    };
  };
}
