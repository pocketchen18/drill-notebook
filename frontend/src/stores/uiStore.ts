import { create } from 'zustand';

type Theme = 'light' | 'dark';

export type AiContextKind = 'none' | 'quiz' | 'wrong' | 'note' | 'bank' | 'manual';

export interface AiPageContext {
  kind: AiContextKind;
  title: string;
  markdown: string;
  route?: string;
  notePageId?: number;
  notebookId?: number;
  questionId?: number;
}

interface UiState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
  aiOpen: boolean;
  setAiOpen: (open: boolean) => void;
  toggleAi: () => void;
  pageContext: AiPageContext;
  setPageContext: (context: AiPageContext) => void;
  clearPageContext: () => void;
}

const emptyContext: AiPageContext = { kind: 'none', title: '无页面上下文', markdown: '' };

export const useUiStore = create<UiState>((set) => ({
  theme: 'light',
  setTheme: (theme) => set({ theme }),
  toggleTheme: () => set((state) => ({ theme: state.theme === 'light' ? 'dark' : 'light' })),
  aiOpen: false,
  setAiOpen: (aiOpen) => set({ aiOpen }),
  toggleAi: () => set((state) => ({ aiOpen: !state.aiOpen })),
  pageContext: emptyContext,
  setPageContext: (pageContext) => set((state) => {
    const prev = state.pageContext;
    if (
      prev.kind === pageContext.kind
      && prev.title === pageContext.title
      && prev.markdown === pageContext.markdown
      && prev.route === pageContext.route
      && prev.notePageId === pageContext.notePageId
      && prev.notebookId === pageContext.notebookId
      && prev.questionId === pageContext.questionId
    ) {
      return state;
    }
    return { pageContext };
  }),
  clearPageContext: () => set((state) => (state.pageContext.kind === 'none' ? state : { pageContext: emptyContext }))
}));
