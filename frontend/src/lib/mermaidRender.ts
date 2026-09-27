import mermaid, { type RenderResult } from 'mermaid';

function removeOwnedArtifacts(id: string, host: HTMLElement, existing: ReadonlySet<Element>): void {
  // Mermaid's normal path writes `d<id>` into the supplied host.  Its error
  // renderer can still fall back to the document body, where it creates the
  // same IDs without a host.  Remove only this call's generated IDs so a
  // concurrent diagram (or unrelated application SVG) is left untouched.
  host.remove();
  for (const artifactId of [`d${id}`, `i${id}`, id]) {
    const artifact = document.getElementById(artifactId);
    if (artifact && artifact !== host && !existing.has(artifact)) artifact.remove();
  }
}

/**
 * Mermaid needs connected DOM for SVG measurement. Own its temporary subtree so
 * even a failed render cannot leave an error diagram at the bottom of the page.
 */
export async function renderMermaid(id: string, code: string): Promise<RenderResult> {
  const artifactIds = [`d${id}`, `i${id}`, id];
  const existingArtifacts = new Set<Element>();
  for (const artifactId of artifactIds) {
    const existing = document.getElementById(artifactId);
    if (existing) existingArtifacts.add(existing);
  }
  const host = document.createElement('div');
  host.dataset.mermaidRenderHost = 'true';
  host.setAttribute('aria-hidden', 'true');
  // Keep layout measurable (not display:none), invisible and out of document flow.
  host.style.cssText = 'position:fixed;inset:0;visibility:hidden;opacity:0;pointer-events:none;overflow:hidden;';
  document.body.append(host);
  try {
    return await mermaid.render(id, code, host);
  } finally {
    // Wait for Mermaid to settle, even if the requesting React view has unmounted.
    // Remove only this render's host; other diagrams may still be in the queue.
    removeOwnedArtifacts(id, host, existingArtifacts);
  }
}
