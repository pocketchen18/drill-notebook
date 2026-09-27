import mermaid from 'mermaid';

let lastTheme: 'dark' | 'neutral' | null = null;

/** Re-init mermaid when app theme changes (safe to call often). */
export function ensureMermaidTheme(appTheme: 'light' | 'dark'): void {
  const mermaidTheme = appTheme === 'dark' ? 'dark' : 'neutral';
  // Mermaid is a process-wide singleton.  A preview or an integration can
  // re-initialize it between renders, so the theme cache alone is not enough
  // to guarantee that parse failures stay suppressed.
  if (lastTheme === mermaidTheme) {
    try {
      if (mermaid.mermaidAPI.getConfig()?.suppressErrorRendering === true) return;
    } catch {
      // Re-initialize below when an older Mermaid build has no readable config.
    }
  }
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: mermaidTheme, suppressErrorRendering: true });
  lastTheme = mermaidTheme;
}

export function readDocumentTheme(): 'light' | 'dark' {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}
