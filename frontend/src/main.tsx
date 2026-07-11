import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>
);
