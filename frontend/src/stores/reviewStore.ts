import { create } from 'zustand';
import type { DueItem, ReviewStats } from '../lib/review';

export interface AnsweredState {
  quality: number;
  responseTime?: number;
  result?: {
    ef: number;
    interval: number;
    repetitions: number;
    nextReview: string;
    status: string;
  };
}

interface ReviewSessionState {
  items: DueItem[];
  index: number;
  answeredIds: Set<number>;
  answerStates: Record<number, AnsweredState>;
  sessionActive: boolean;

  startSession: (items: DueItem[]) => void;
  nextItem: () => void;
  prevItem: () => void;
  goToItem: (idx: number) => void;
  markAnswered: (scheduleId: number, state: AnsweredState) => void;
  endSession: () => void;
}

export const useReviewStore = create<ReviewSessionState>((set, get) => ({
  items: [],
  index: 0,
  answeredIds: new Set(),
  answerStates: {},
  sessionActive: false,

  startSession: (items) => set({
    items,
    index: 0,
    answeredIds: new Set(),
    answerStates: {},
    sessionActive: true,
  }),

  nextItem: () => {
    const { index, items } = get();
    if (index < items.length - 1) set({ index: index + 1 });
  },

  prevItem: () => {
    const { index } = get();
    if (index > 0) set({ index: index - 1 });
  },

  goToItem: (idx) => set({ index: idx }),

  markAnswered: (scheduleId, state) => {
    const { answeredIds, answerStates } = get();
    const newAnsweredIds = new Set(answeredIds);
    newAnsweredIds.add(scheduleId);
    set({
      answeredIds: newAnsweredIds,
      answerStates: { ...answerStates, [scheduleId]: state },
    });
  },

  endSession: () => set({
    items: [],
    index: 0,
    answeredIds: new Set(),
    answerStates: {},
    sessionActive: false,
  }),
}));
