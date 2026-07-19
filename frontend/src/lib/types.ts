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
  questionIds: number[];
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

export interface AiConfig {
  provider: string;
  endpoint: string;
  model: string;
  hasKey: boolean;
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

export interface ChatMessage {
  id?: number;
  role: 'user' | 'assistant' | 'system';
  content: string | ChatContentPart[];
  displayContent?: string;
  createdAt?: string;
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
