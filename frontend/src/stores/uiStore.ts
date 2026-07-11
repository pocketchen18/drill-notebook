import { create } from 'zustand';

type Theme = 'light' | 'dark';

interface UiState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
}

export const useUiStore = create<UiState>((set) => ({
  theme: 'light',
  setTheme: (theme) => set({ theme }),
  toggleTheme: () => set((state) => ({ theme: state.theme === 'light' ? 'dark' : 'light' }))
}));
