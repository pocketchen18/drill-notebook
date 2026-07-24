import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from '@arco-design/web-react';
import '@arco-design/web-react/dist/css/arco.css';
import 'katex/dist/katex.min.css';
import './styles/app.css';
import { App } from './App';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 10_000 },
    mutations: { retry: false }
  }
});

/** Apply theme before first paint when possible (Electron may restore theme after mount). */
function bootstrapThemeAttribute(): void {
  try {
    const saved = localStorage.getItem('drill-notebook-theme');
    if (saved === 'dark' || saved === 'light') {
      document.documentElement.dataset.theme = saved;
      if (saved === 'dark') document.body.setAttribute('arco-theme', 'dark');
      else document.body.removeAttribute('arco-theme');
    }
  } catch {
    /* ignore */
  }
}
bootstrapThemeAttribute();

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <App />
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>
);
