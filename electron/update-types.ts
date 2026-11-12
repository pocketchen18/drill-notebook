/** Fixed IPC contract shared by the main process and the two renderers. */
export interface UpdateSettings {
  automaticUpdate: boolean;
  automaticCheck: boolean;
  intervalHours: number;
  startupReminder: boolean;
}

export interface UpdateAsset {
  name: string;
  url: string;
  size: number;
  digest?: string;
}

export interface UpdateRelease {
  version: string;
  tag: string;
  name: string;
  body: string;
  publishedAt: string;
  url: string;
  prerelease: boolean;
  asset?: UpdateAsset;
  checksum?: UpdateAsset;
}

export type NotesRange = 'since' | 10 | 20 | 50 | 100;
export interface UpdateNotes {
  releases: UpdateRelease[];
  range: NotesRange;
  cached: boolean;
  error?: string;
}

export type UpdatePhase = 'idle' | 'downloading' | 'verifying' | 'preparing' | 'blocked' | 'restarting' | 'failed';
export interface UpdateProgress {
  phase: UpdatePhase;
  version?: string;
  received: number;
  total?: number;
  bytesPerSecond: number;
  message?: string;
}

export interface UpdatePresentation {
  id: number;
  kind: 'available' | 'progress' | 'history';
  version?: string;
}

export interface AppUpdateState {
  currentVersion: string;
  development: boolean;
  installSupported: boolean;
  settings: UpdateSettings;
  checking: boolean;
  checkStatus: 'unchecked' | 'current' | 'available' | 'error';
  checkError?: string;
  lastCheckAt?: number;
  lastReminderAt?: number;
  skippedVersion?: string;
  failedVersion?: string;
  failure?: string;
  sinceVersion?: string;
  target?: UpdateRelease;
  updateVisible: boolean;
  startupBusy: boolean;
  progress: UpdateProgress;
  presentation?: UpdatePresentation;
}

export interface UpdatePreparationRequest { id: string; }
export interface UpdatePreparationResult { id: string; ok: boolean; reason?: string; }

export interface AppUpdaterApi {
  getState(): Promise<AppUpdateState>;
  setSettings(patch: Partial<UpdateSettings>): Promise<AppUpdateState>;
  check(): Promise<AppUpdateState>;
  openAvailable(): Promise<void>;
  openHistory(): Promise<void>;
  closePresentation(id: number): Promise<void>;
  notes(range: NotesRange, version?: string, refresh?: boolean): Promise<UpdateNotes>;
  skip(version: string): Promise<void>;
  restoreReminder(): Promise<void>;
  install(version: string): Promise<void>;
  cancelDownload(): Promise<void>;
  resumeInstall(): Promise<void>;
  rendererReady(): Promise<void>;
  prepared(result: UpdatePreparationResult): Promise<void>;
  onState(listener: (state: AppUpdateState) => void): () => void;
  onPrepare(listener: (request: UpdatePreparationRequest) => void): () => void;
  onPreparationEnd(listener: () => void): () => void;
  readAssistantDrafts(): Promise<unknown>;
  saveAssistantDrafts(value: unknown): Promise<void>;
}
