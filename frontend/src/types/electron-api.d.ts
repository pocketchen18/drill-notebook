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
  video: {
    fetchTitle: (url: string) => Promise<string | null>;
  };
}

interface Window {
  api: ElectronApi;
}
