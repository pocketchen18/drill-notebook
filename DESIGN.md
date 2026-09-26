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

The CSS variables in `frontend/src/styles/app.css` remain authoritative. New
workspace styles must use these tokens rather than introduce route-local raw
colors. Since v0.6 the same file also overrides Arco's theme variables on
`body` / `body[arco-theme='dark']` (`--arcoblue-*`, radius, text / border /
fill), so Arco components inherit this palette without per-component patches.

| Role | Token | Light | Dark | Usage |
|---|---|---:|---:|---|
| Page canvas | `--page-bg` | `#f4f6fa` | `#131519` | App and route background |
| Working surface | `--panel-bg` | `#ffffff` | `#1b1e24` | Editor and question content |
| Sider surface | `--sider-bg` | `#fafbfd` | `#16181d` | Global navigation rail |
| Whisper divider | `--line` | `#e4e7ee` | `#2a2f38` | Pane and row separation |
| Tonal hover | `--hover-bg` | `#eff2f7` | `#242931` | Hovered explorer rows |
| Tonal pane | `--subtle-bg` | `#f6f8fb` | `#20242b` | Explorer and command surfaces |
| Primary text | `--text` | `#1e2433` | `#e2e6ee` | Body and controls |
| Strong text | `--text-strong` | `#0f1420` | `#f7f8fa` | Titles and active rows |
| Secondary text | `--muted` | `#6b7385` | `#98a2b3` | Metadata and hints |
| Primary action | `--accent` | `#2f54eb` | `#8da2ff` | Accent text, borders and focus |
| Primary fill | `--accent-fill` | `#2f54eb` | `#4b6bf0` | Solid buttons and badges (white text) |
| Selected surface | `--accent-soft` | `#eef2ff` | `#1f2a4d` | Active explorer row |
| Secondary selection | `--accent-soft-2` | `#f5f7ff` | `#1b2340` | Multi-selected content |
| Selection divider | `--accent-border` | `#c7d2fe` | `#3b52a8` | Selected boundaries |
| Destructive | `--danger` | `#e5484d` | `#ff7b7b` | Delete commands |

### Rules

- Accent blue marks actions, selection, and focus only; it is not decoration.
- Explorer/content separation uses tonal shift plus one divider, not nested
  cards or shadows.
- Error, warning, and success colors keep their existing semantic tokens.
- Dark mode must preserve the same hierarchy rather than merely invert colors.

## 3. Typography

### Font Stack

- UI and reading: `"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei",
  "PingFang SC", sans-serif` (system fonts only).
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

- **Structure**: title/plan row, editor toolbar docked to the top of the canvas
  scroller, and the document canvas. The toolbar is grouped — history, block
  types and lists, inline formatting, insertion commands — with find, outline,
  focus mode, and new-page actions in an aside row. Formatting shortcuts are
  handled inside the editor and are not user-configurable; find and replace are
  configurable editor-scope actions.
- **States**: default, saving silently, focus mode, empty block, long content,
  dark, reduced motion.
- **Accessibility**: all formatting and insertion commands remain visible and
  keyboard reachable; focus mode preserves its current semantics. Selection
  actions expose accessible names and keep compact desktop targets at least
  32px.
- **Selection actions**: a zero-dependency floating toolbar appears for a
  non-empty text or node selection above the selection, flips below it when the
  sticky toolbar would cover it, hides while the mouse is still dragging, and
  never appears inside a code block or a table cell selection. It exposes the
  block type, bold, italic, underline, strike, inline code, link, text and
  background color, superscript, subscript, conversion to inline LaTeX, clear
  formatting, and destructive deletion; deletion stays undoable through the
  editor history. A node selection exposes duplicate and delete instead. A
  collapsed caret inside a link shows a link card (address, open, edit, remove);
  the link editor opens from the toolbar, the card, or its shortcut, and
  external links only ever open through the main process, which allows
  http/https.
- **Colors**: text and background colors are stored as semantic names
  (`data-text-color` / `data-color`) resolving to light/dark token pairs. No
  inline color is written, so colors pasted from outside are not preserved.
- **Outline**: the toolbar can toggle a heading outline. In focus mode the
  outline has a persisted left/right preference and is fixed to the viewport
  edge. In preview mode it is right-aligned beside the page explorer by
  default; a separate persisted switch can exchange the explorer and outline
  positions. The canvas reserves 260px on the active side while it is open.
- **Block editing**: Markdown and Mermaid text areas commit on blur as well as
  their existing keyboard commands, so clicking outside an active block does
  not discard the draft. Escape is a true cancel path; a blur caused by
  removing the input cannot submit the cancelled draft. The same rule applies
  to display and inline LaTeX blocks.
- **Block handle**: every top-level block and every list/task item shares one
  hover handle in the reserved 48px gutter, aligned with the block's first line.
  It uses TipTap's native drag contract (serialized slice on the transfer,
  `view.dragging` set for the drop, block DOM as the drag image), remains usable
  on coarse pointers — where it follows the caret — and on narrow canvases
  without adding another tab stop. Its menu inserts a block below (which opens
  the slash catalog), turns the block into any catalog type, duplicates (one
  history step), moves it up/down, indents or outdents list items, and deletes.
  Atom blocks keep only duplicate, move, and delete; their former inner handles
  and reserved left padding are gone. Because no inner `data-drag-handle`
  remains, a native drag starting inside a block's non-editable body is
  cancelled — otherwise a slightly moving click in a source field drags the
  whole block away. While a block is dragged, pointing within 72px of the
  dock's bottom edge or the viewport's bottom edge scrolls the canvas, faster
  the deeper the pointer goes, so a block can move beyond the visible area; the
  handle finishes its drag state on document-level drop/dragend because the
  moved source node (and the handle with it) unmounts before its own dragend.
- **Content sync**: the editor owns the document while mounted. Content objects
  it emitted through `onChange` and the page hands back are echoes and never
  reset the document — an echo can lag one transaction behind and, applied with
  `setContent` in the middle of a node-view render, would swallow the block
  being inserted. Only a foreign object (a server snapshot) replaces the
  document; switching pages remounts the editor by key.
- **Move selection cleanup**: an in-editor move collapses the resulting node or
  range selection to the nearest text cursor. When a complete heading text
  selection is moved, the empty source heading shell is normalized to a
  paragraph; deleting characters until a heading is empty still preserves its
  heading style.
- **Slash menu**: `/` or `／` opens the block catalog at the start of a block or
  after whitespace, and `、` at the start of a block only; it never opens inside
  a code block, from a paste or drop, when the caret merely moves into older
  text, or while the IME is composing. Filtering matches the Chinese label, the
  English alias, and pinyin initials or full spelling, with prefix matches
  first. Arrow keys cycle, Enter or Tab runs the command after removing the
  typed query, Escape closes it for that trigger. Removing the query and the
  command itself form one transaction (one undo step; no half-done document is
  emitted); file and video commands, which insert later, commit the removal
  first. A newly inserted source block receives focus in its source field once
  its DOM is attached. The menu portals to the body,
  opens under the caret, flips above when there is no room, and keeps the active
  item scrolled into view.
- **Content types**: links (typed, pasted over a selection, or set from the link
  editor), task lists (nested, `[ ]` / `[x]` input rules, dimmed when checked),
  underline, highlight, superscript, subscript, tables inserted as 3×3 with a
  header row, and code blocks with syntax highlighting — a common language set,
  a language select that keeps unknown aliases as-is, and a copy button with a
  check confirmation.
- **Table affordances**: a floating row/column bar appears above the table the
  caret is in, offering insert row above/below, insert column before/after,
  header-row toggle, merge/split when available, and delete row, column, or
  table behind a "more" menu.
- **Markdown paste**: plain-text clipboard content that looks like Markdown, or
  arrives from VS Code, becomes native rich text through the editor's own paste
  path in a single undo step — headings, lists, task lists, tables, quotes,
  fenced code with its language, `mermaid` fences, `$$` / `$` math under pandoc
  boundary rules, and links, with images degrading to links. A code block, a
  plain-text paste, or clipboard content carrying HTML keeps the literal paste,
  and multi-line VS Code code becomes a code block. The Markdown block node
  remains available from the catalog and insertion menu for whole-block source
  editing.
- **Images**: a pasted or dropped image uploads as an attachment and renders as
  a figure at natural size capped to the content width, with a hover bar for the
  small/medium/large/original width presets, download, and collapse back to a
  card. Image blocks stored earlier keep their card form.
- **Find and replace**: the canvas docks a search bar at its top right that
  mirrors the knowledge-card search bar — same treatment, `N / M` or "no match"
  count, the same accessible names for previous, next, and close, Enter and
  Shift+Enter to step, Escape to close and return focus to the document, yellow
  matches with an orange current match, unbolded so the text does not reflow. It
  adds case sensitivity and a replace row (replace, replace all). Matches are
  decorations rather than DOM marks, because marks inserted into an editable
  region are observed as user edits; a replace-all stays a single undo step and
  the count refreshes as the document changes. Its bindings are configurable
  editor-scope shortcuts, listed on the settings page like the knowledge-card
  ones.
- **Floating surfaces**: every floating layer portals to `document.body`, since
  the canvas is a size container that would otherwise become the containing
  block for `position: fixed` descendants and clip them. A layer hides instead
  of overlapping the sticky toolbar, using the dock's bottom edge as its visible
  boundary, sits above the dock and below Arco overlays, and repositions on
  scroll and resize with measurements taken after each render.
- **Document end**: clicking the empty area below the last block moves the caret
  to the end of the document and appends a paragraph only when the last block
  cannot hold text. Opening an existing page never edits it.
- **Mermaid errors**: previews render through an owned, hidden connected host;
  syntax failures remain represented inside the block and must not append
  error SVGs to the document body. The renderer also removes body-level
  fallback nodes keyed by its own render ID while preserving unrelated nodes.
- **Empty and responsive states**: an empty first paragraph exposes the
  configured placeholder and uses the accent caret/focus treatment. At narrow
  widths, an open outline becomes an overlay drawer so the readable document
  measure is not squeezed; in normal mode toolbar labels collapse based on the
  editor container width (1120px), while every command remains keyboard
  reachable through its accessible name and title.
- **Layout**: no nested content scroll; readable document flow with a 760px
  normal-mode reading measure and a 920px focus-mode measure. The shell uses
  `overflow: clip` so the rounded corner is preserved without blocking the
  sticky toolbar, and the editor reserves the dock height as scroll margin and
  threshold so scrolling to the caret never hides it under the toolbar.
- **No new debt**: this round adds no accepted debt. Existing entries below are
  unchanged.

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
  framed tool needs containment. Outside the workspace (v0.6 page primitives)
  panels use `--radius-lg` (12px) and study cards `--radius-xl` (16px) with the
  `--shadow-xs` / `--shadow-sm` tiers; the workspace itself still does not
  float.

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
| Existing global raw color values | `frontend/src/styles/app.css` outside workspace selectors | v0.6 moved page styles onto the shared tokens; the remaining raw values are intentional (search-hit marks, brand gradient on the icon / AI fab, PowerPoint brand orange, `#000` video backdrop). | Future design-system consolidation |

No new design debt may be added silently. Critical accessibility or command
parity failures block delivery.
