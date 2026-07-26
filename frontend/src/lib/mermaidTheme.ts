import mermaid from 'mermaid';

let lastTheme: 'dark' | 'neutral' | null = null;

/** Re-init mermaid when app theme changes (safe to call often). */
export function ensureMermaidTheme(appTheme: 'light' | 'dark'): void {
  const mermaidTheme = appTheme === 'dark' ? 'dark' : 'neutral';
  if (lastTheme === mermaidTheme) return;
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: mermaidTheme });
  lastTheme = mermaidTheme;
}

export function readDocumentTheme(): 'light' | 'dark' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}
