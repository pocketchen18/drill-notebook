export type QuestionType = 'single' | 'multiple' | 'fill' | 'true_false' | 'essay';

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

export interface KnowledgePoint {
  id: number;
  bankId?: number;
  title: string;
  content: string;
  category?: string;
  tags: string[];
  headingPath?: string[];
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
}

export interface AiChatSession {
  id: number;
  title: string;
  archived?: boolean;
  model?: string;
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

export interface ChatMessage {
  id?: number;
  role: 'user' | 'assistant' | 'system';
  content: string | ChatContentPart[];
  displayContent?: string;
  createdAt?: string;
  citations?: ChatCitation[];
  /** 检索降级/状态轻量提示（Task 14）；仅随当前会话 response 保留，不持久化。 */
  notice?: RetrievalNotice;
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
