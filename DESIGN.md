# Drill Notebook Design System

This document extracts the existing visual system and defines the workspace
primitives used by the Notebook and Question Bank redesign. It is a contract,
not a request to restyle unrelated routes.

## 1. Atmosphere & Identity

Drill Notebook is a quiet study workbench: operational, readable, and dense
without feeling compressed. Its signature is a paper-like working canvas held
by tonal side panes and whisper dividers. The interface should recede while the
user reads, edits, compares, and selects study material. Notion contributes the
content-first restraint, Feishu contributes contextual command placement,
Obsidian contributes list-detail spatial clarity, and Anki contributes the
dense scanning rhythm for questions. Their branding and product models are not
copied.

Primary personas:

- A keyboard-heavy learner switching rapidly between pages and questions.
- A long-session note author who needs stable focus, autosave, and readable
  line lengths.
- A user with low vision or reduced motor precision who needs visible focus,
  persistent selection controls, and non-disappearing commands.

## 2. Color

### Palette

The existing CSS variables remain authoritative. New workspace styles must use
these tokens rather than introduce route-local raw colors.

| Role | Token | Light | Dark | Usage |
|---|---|---:|---:|---|
| Page canvas | `--page-bg` | `#f5f7fa` | `#17181a` | App and route background |
| Working surface | `--panel-bg` | `#ffffff` | `#232527` | Editor and question content |
| Whisper divider | `--line` | `#e5e6eb` | `#3b3e43` | Pane and row separation |
| Tonal hover | `--hover-bg` | `#f2f3f5` | `#30343a` | Hovered explorer rows |
| Tonal pane | `--subtle-bg` | `#f7f8fa` | `#1f2124` | Explorer and command surfaces |
| Primary text | `--text` | `#1d2129` | `#e5e6eb` | Body and controls |
| Strong text | `--text-strong` | `#1d2129` | `#f2f3f5` | Titles and active rows |
| Secondary text | `--muted` | `#86909c` | `#a9abb2` | Metadata and hints |
| Primary action | `--accent` | `#155eef` | `#6aa1ff` | Primary controls and focus |
| Selected surface | `--accent-soft` | `#edf4ff` | `#182c4b` | Active explorer row |
| Secondary selection | `--accent-soft-2` | `#f4f8ff` | `#1c2b40` | Multi-selected content |
| Selection divider | `--accent-border` | `#b8d3ff` | `#4078d8` | Selected boundaries |
| Destructive | `--danger` | `#f53f3f` | `#ff6b6b` | Delete commands |

### Rules

- Accent blue marks actions, selection, and focus only; it is not decoration.
- Explorer/content separation uses tonal shift plus one divider, not nested
  cards or shadows.
- Error, warning, and success colors keep their existing semantic tokens.
- Dark mode must preserve the same hierarchy rather than merely invert colors.

## 3. Typography

### Font Stack

- UI and reading: `"Segoe UI", "Microsoft YaHei", sans-serif`.
- Code: `Consolas, monospace` where the current editor already uses it.
- No new webfont or font dependency is introduced.

### Scale

| Role | Size | Weight | Line height | Usage |
|---|---:|---:|---:|---|
| App route title | 20px | 700 | 1.35 | Existing global topbar only |
| Workspace context title | 16px | 600 | 1.4 | Current bank/page title |
| Pane heading | 14px | 600 | 1.4 | Explorer heading |
| Body | 14px | 400 | 1.65-1.75 | Questions and note text |
| UI label | 13px | 500-600 | 1.4 | Buttons and selection labels |
| Metadata | 12px | 400-600 | 1.4 | Counts, chapter, type hints |

Letter spacing remains `0`. Route-local H1 and explanatory hero copy are
removed because the global topbar already identifies the route.

## 4. Spacing & Layout

### Base Unit

Spacing is based on 4px. The touched workspace uses:

| Token | Value | Usage |
|---|---:|---|
| `--workspace-space-1` | 4px | Icon/metadata gap |
| `--workspace-space-2` | 8px | Compact row/control gap |
| `--workspace-space-3` | 12px | Row inset and command clusters |
| `--workspace-space-4` | 16px | Narrow route padding |
| `--workspace-space-5` | 20px | Standard route padding |
| `--workspace-space-6` | 24px | Wide route padding |

### Workspace Geometry

- Route padding: 16px at constrained widths, 20px by default, 24px when the
  route container has room. These values are owned by the route CSS contract,
  not duplicated as component inline styles.
- Command row: `min-height: 44px`; controls wrap before clipping.
- List-detail body: `232px minmax(0, 1fr)` when route content is at least
  760px wide.
- Below 760px the explorer stacks above content, uses `width: 100%`, and its
  list is capped at 240px without exceeding the available container width.
- `.app-shell .arco-layout-content` remains the route document scroll owner.
- The explorer list is the only named nested vertical scroll region. Wheel
  input must chain to route scrolling at both list boundaries.
- The editor and question content remain in document flow. No second content
  scrollbar is introduced.
- The global Sider remains 224px expanded, 56px collapsed, and 0px hidden.

## 5. Components

### Route Workspace (`.route-workspace`)

- **Structure**: semantic `main` containing a command row and list-detail body.
- **Variants**: notebook, bank, notebook focus mode.
- **States**: loading, empty, populated, long labels, dark, reduced motion.
- **Accessibility**: one `main` landmark; no focus reordering; no horizontal
  primary-content scroll.
- **Layout**: route document scroll is owned by Arco Layout Content.

### Route Command Row (`.route-command-row`)

- **Structure**: context selector/title cluster plus direct command cluster.
- **Spacing**: 8px/12px gaps; 44px minimum block size.
- **States**: default, wrapping, loading, disabled, keyboard focus.
- **Accessibility**: all current commands stay one click away. Desktop command
  targets are at least 32px; coarse-pointer targets are at least 44px.
- **Motion**: none beyond existing button state transitions.

### Local Explorer (`.local-explorer`)

- **Structure**: `aside` with `.local-explorer__header` and
  `.local-explorer__list`.
- **Variants**: notebook pages and question banks.
- **States**: hover, selected, multi-selected, rename, loading, empty, long
  label, destructive action focus.
- **Accessibility**: existing checkboxes remain visible. Existing Enter, F2,
  double-click, Escape, blur, and focus-select behavior remains unchanged.
- **Layout**: fixed 232px track in split mode; list is the sole nested scroll.

### Dense Content Row (`.dense-content-row`)

- **Structure**: persistent selection control, complete primary content,
  metadata, and direct actions.
- **Variants**: notebook page row, bank row, fully expanded question row.
- **States**: hover, current, selected-for-export, rename, pending, disabled.
- **Accessibility**: visual order equals DOM/tab order; destructive icons have
  accessible labels; content wraps or truncates intentionally.
- **Constraint**: questions keep stem, options, chapter, type, edit, delete, and
  checkbox visible by default.

### Editor Canvas (`.editor-canvas`)

- **Structure**: title/plan row, direct editor toolbar, document canvas.
- **States**: default, saving silently, focus mode, empty block, long content,
  dark, reduced motion.
- **Accessibility**: all formatting and insertion commands remain visible and
  keyboard reachable; focus mode preserves its current semantics.
- **Layout**: no nested content scroll; readable document flow.

## 6. Motion & Interaction

| Type | Duration | Easing | Usage |
|---|---:|---|---|
| Micro | 120-160ms | ease-out | Hover, focus, pressed feedback |
| Existing Sider | 240-280ms | existing cubic-bezier | Preserved global navigation |

- Animate only `transform`, `opacity`, and color where already established.
- Do not animate workspace grid tracks, width, height, padding, or scroll
  position.
- `prefers-reduced-motion: reduce` collapses non-essential transitions to 1ms.
- Selection, rename, loading, and save states never rely on animation alone.

## 7. Depth & Surface

Strategy: **tonal shift with whisper dividers**.

- Route canvas: `--page-bg`.
- Explorer: `--subtle-bg` with a single `--line` divider.
- Editor/question content: `--panel-bg` without outer nested-card framing.
- Rows: transparent at rest, `--hover-bg` on hover, accent-soft tokens when
  selected.
- Shadows are reserved for existing overlays, drawers, modals, and popovers.
  Workspace panes do not float.
- Radius stays functional: 4-6px for row controls, 8px only where an actual
  framed tool needs containment.

## 8. Accessibility Constraints & Accepted Debt

### Constraints

- WCAG 2.2 AA target: 4.5:1 body contrast, 3:1 large text and control boundary.
- Every interactive element retains a visible `:focus-visible` indication.
- Keyboard command paths and labels remain unchanged after relocation.
- Long CJK labels, long stems, unbroken URLs, empty lists, and narrow container
  states cannot produce horizontal primary-content overflow.
- Coarse-pointer targets are 44px minimum. Desktop compact targets are 32px
  minimum and keep accessible names/tooltips.
- The full Electron app is verified at 1100, 1180, 1280, 1440, and 1896px.
  The 375/759/760/761/768/1280 matrix is an isolated route-container harness,
  not a claim that Electron supports a 375px window.

### Accepted Debt

| Item | Location | Why accepted | Exit condition |
|---|---|---|---|
| Page-switch autosave race | `frontend/src/pages/NotebookPage.tsx` | Current observable behavior is explicitly locked: a switch inside the 400ms debounce PUTs the captured prior page id with latest pending content. The owner did not authorize behavior change in this visual task. | Separate behavior task with explicit owner approval and migration test |
| Existing global raw color values | `frontend/src/styles/app.css` outside workspace selectors | This task extracts and uses the existing theme; it is not a whole-app palette rewrite. | Future design-system consolidation |

No new design debt may be added silently. Critical accessibility or command
parity failures block delivery.
