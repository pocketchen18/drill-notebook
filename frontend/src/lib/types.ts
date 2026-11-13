export type QuestionType = 'single' | 'multiple' | 'fill' | 'true_false' | 'essay';
import type { ChatResultView } from './aiStream';

export interface QuestionOption {
  key: string;
  text: string;
}

export interface Question {
  id: number;
  bankId: number;
  type: QuestionType;
  stem: string;
  options: QuestionOption[];
  answer?: string;
  analysis?: string;
  difficulty?: number;
  tags?: string[];
  chapter?: string;
  groupId?: string;
  orderInGroup?: number;
}

/** 错题本条目：题目 + 累计作答统计（错误次数 / 作答次数 / 错误率 0~1）。 */
export interface WrongBookEntry extends Question {
  wrongCount: number;
  attemptCount: number;
  errorRate: number;
}

export interface KnowledgePoint {
  id: number;
  bankId?: number;
  title: string;
  content: string;
  category?: string;
  tags: string[];
  headingPath?: string[];
  sortIndex?: number;    // 手动排序后的顺序（越小越靠前；未排序时由后端按 category/title 字母序）
  questionIds: number[];
  hasOriginal?: boolean;  // 是否已存原文（即「已总结」标记）
  createdAt?: string;
  updatedAt?: string;
}

export interface Bank {
  id: number;
  name: string;
  description?: string;
  sourceType?: string;
  createdAt?: string;
  questionCount?: number;
}

export interface Notebook {
  id: number;
  title: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface NotePage {
  id: number;
  notebookId: number;
  parentId?: number;
  title: string;
  sortOrder?: number;
  content: Record<string, unknown>;
  updatedAt?: string;
}

export interface QuizSession {
  sessionId: string;
  questions: Question[];
}

export interface GradingResult {
  version?: number;
  score?: number;
  suggestedCorrect?: boolean;
  confidence?: number;
  explanation?: string;
  model?: string;
  available?: boolean;
  message?: string;
}

export interface SubmitResult {
  isCorrect: boolean | null;
  correctAnswer: string;
  analysis?: string;
  gradingStatus?: 'deterministic' | 'ai_suggested' | 'unavailable' | 'ungraded';
  grading?: GradingResult | null;
}

/** 单套模型连接（主模型或导入兜底） */
export interface AiModelSlot {
  provider: string;
  endpoint: string;
  model: string;
  hasKey: boolean;
  authMode?: 'api_key' | 'none';
  /** API 格式：chat_completions（默认）| anthropic。 */
  apiFormat?: 'chat_completions' | 'anthropic';
  /** 是否流式传输（默认开）。 */
  streaming?: boolean;
  /** 是否允许模型调用工具（如 search_memories）；仅对话槽位使用。 */
  readonly tools?: boolean;
}

/**
 * AI 配置：主模型（对话/总结/计划）与导入兜底（PDF/MD/JSON/知识点解析）分轨。
 * 顶层 provider/endpoint/model/hasKey 与 chat 相同，兼容旧代码。
 */
export interface AiConfig extends AiModelSlot {
  chat?: AiModelSlot;
  import?: AiModelSlot;
  embedding?: EmbeddingSlot;
}

/** Embedding 配置 slot（Task 12）：provider 为 disabled/local/openai/ollama。 */
export interface EmbeddingSlot {
  provider: string;
  endpoint: string;
  model: string;
  dimensions: number;
  hasKey: boolean;
  enabled: boolean;
  consent: boolean;
  /** 未授权保存时后端返回 CONSENT_REQUIRED。 */
  code?: string;
}

/** 本地模型目录项（Task 10/13）：内置元数据 + 安装状态。 */
export interface EmbeddingCatalogModel {
  id: string;
  providerModelId: string;
  artifactRevision: string;
  displayName: string;
  license: string;
  languages: string[];
  dimensions: number;
  inventorySizeBytes: number;
  installationState: 'AVAILABLE' | 'DOWNLOADING' | 'VERIFYING' | 'READY' | 'UNINSTALLING' | 'FAILED' | 'PAUSED';
  downloadError?: string | null;
  downloadProgress?: {
    jobId?: string;
    totalBytes?: number;
    files?: Record<string, number>;
    [key: string]: unknown;
  } | null;
  /** 'online' for HuggingFace models, undefined for built-in. */
  source?: string;
}

export interface EmbeddingCatalog {
  catalogVersion: number;
  models: EmbeddingCatalogModel[];
  onlineStale?: boolean;
  onlineError?: string | null;
}

/** 向量索引状态（GET /api/ai/retrieval/status）。 */
export interface RetrievalStatus {
  scope: string;
  notebookId?: number | null;
  totalPages: number;
  totalChunks: number;
  indexedChunks: number;
  staleChunks: number;
  queuedJobs: number;
  failedJobs: number;
  coverage: number;
  indexState: string;
  embeddingSpaceId?: string | null;
  provider?: string | null;
  /** 当前 provider 是否可用（本地模型未编译 worker 时为 false）。 */
  providerReady?: boolean;
}

export interface AiChatSession {
  id: number;
  title: string;
  sessionLink?: string;
  titleSource?: 'temporary' | 'manual' | 'ai' | 'legacy';
  titleStatus?: 'pending' | 'idle' | 'generating' | 'ready' | 'failed';
  titleRevision?: number;
  canGenerateTitle?: boolean;
  archived?: boolean;
  model?: string;
  /** 置顶时刻；null 表示未置顶。先置顶的排在前面。 */
  pinnedAt?: string | null;
  /** 会话自己选择的模型配置；null 表示跟随主模型。 */
  modelConfigId?: string | null;
  /** 会话自己选择的思考档位；null 表示模型默认。 */
  reasoningEffort?: string | null;
  tags?: string;
  createdAt?: string;
  updatedAt?: string;
  messageCount?: number;
}

export interface ChatContentPart {
  type: 'text' | 'image_url';
  text?: string;
  image_url?: { url: string };
}

/** 笔记检索引用（后端只返回 snippet，绝不含 chunk 全文）。 */
export interface ChatCitation {
  corpusType: string;
  notebookId: number;
  pageId: number;
  chunkId: number;
  title: string;
  headingPath: string;
  snippet: string;
  matchTypes: string[];
  ftsRank?: number | null;
  vectorRank?: number | null;
  rrfScore?: number | null;
}

export interface RetrievalNotice {
  code: string;
  message?: string;
}

/** 发送给 /api/ai/chat 的检索选项；关闭时不发送该字段以保持旧契约。 */
export interface RetrievalOptions {
  enabled: boolean;
  scope: 'current' | 'all';
  notebookId?: number;
}

/** 本轮 done 的统一结果，且身份已确认（requestId 必然在场，否则界面无法定位到某一轮）。 */
export type TurnResult = ChatResultView & { requestId: string };

export interface ChatMessage {
  recovery?: import('./aiRecovery').AiRecovery | null;
  supplements?: import('./chatQueue').ChatSupplement[];
  toolActivities?: import('./toolActivity').ToolActivity[];
  toolPhase?: import('./toolActivity').ToolPhase;
  id?: number;
  role: 'user' | 'assistant' | 'system';
  content: string | ChatContentPart[];
  displayContent?: string;
  /** 提交这条提问时绑定的业务页面；没有业务页面时为空。 */
  page?: { title: string; link?: string };
  /** 这一轮实际使用的模型与思考档位；旧记录没有。 */
  model?: string;
  reasoningEffort?: string;
  /** 供应商回报的本轮累计用量（可能包含多次模型请求）；未回报时没有。 */
  usage?: { requests: number; inputTokens: number; outputTokens: number };
  createdAt?: string;
  citations?: ChatCitation[];
  /** 检索降级/状态轻量提示（Task 14）；仅随当前会话 response 保留，不持久化。 */
  notice?: RetrievalNotice;
  /** 思考链（CoT）：仅流式当前消息展示，不持久化。 */
  reasoning?: string;
  /** 流式进行中标记。 */
  streaming?: boolean;
  /**
   * 本轮 done 的统一结果快照（含 requestId 与记忆/上下文/用量四块）：只在内存里，
   * 供记忆徽标与上下文检查器定位这一轮；不落 localStorage，也不进导出。
   */
  turn?: TurnResult;
  /**
   * 本轮幂等身份（UUIDv7）：历史行与本地气泡共用它，落地后靠它把本地气泡换成服务端行，
   * 也靠它把徽标/检查器挂回正确的那一轮。legacy 行没有它。
   */
  requestId?: string;
  /** 前端稳定 key：消息首次创建时分配，后续 refetch/合并复用，避免列表 remount 造成滚动抖动。 */
  _key?: string;
}

export type PlanResourceType = 'question' | 'knowledge_point' | 'note_page';
export type PlanSource = 'manual' | 'session_recommend';
export type PlanStatus = 'todo' | 'done';

export interface StudyPlanItem {
  id: number;
  groupId: number;
  planDate: string;
  resourceType: PlanResourceType;
  resourceId: number;
  title: string;
  note?: string;
  status: PlanStatus;
  resourceMissing?: boolean;
  completedAt?: string;
}

export interface StudyPlanGroup {
  id: number;
  planDate: string;
  title: string;
  note?: string;
  source: PlanSource;
  doneCount: number;
  totalCount: number;
  items: StudyPlanItem[];
}

export interface StudyPlanDay {
  date: string;
  groups: StudyPlanGroup[];
}

export interface StudyPlanRangeResponse {
  days: StudyPlanDay[];
}

export interface PlanCandidate {
  resourceType: PlanResourceType;
  resourceId: number;
  title: string;
}

export interface NoteAttachment {
  id: number;
  pageId: number;
  fileName: string;
  storagePath: string;
  mimeType: string;
  fileSize: number;
  sha256: string | null;
  createdAt: string;
}

// ---------------- 数据管理（备份） ----------------

export interface BackupConfig {
  /** 备份目录；空串表示使用默认目录（APP_ROOT/backups）。 */
  directory: string;
  /** 自动备份间隔（小时）；0 = 关闭自动备份。 */
  autoIntervalHours: number;
  /** 最大备份数，超出时删除最旧备份。 */
  maxCount: number;
  /** 精简备份：仅备份数据库，不含附件。 */
  lite: boolean;
}

export interface BackupEntry {
  name: string;
  sizeBytes: number;
  createdAt: string;
  lite: boolean;
}

export interface BackupStatus {
  directory: string;
  autoEnabled: boolean;
  autoIntervalHours: number;
  maxCount: number;
  lite: boolean;
  running: boolean;
  lastBackupAt: string | null;
  nextBackupAt: string | null;
  lastError: string | null;
  backupCount: number;
}
