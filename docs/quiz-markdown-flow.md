# 题库 Markdown 解析与渲染流程

本文档记录项目中题库（quiz）相关的 Markdown 解析逻辑，涵盖前端渲染、序列化、评分和 AI 上下文注入。

## 目录

1. [数据流总览](#1-数据流总览)
2. [渲染路径：Markdown + LaTeX → HTML](#2-渲染路径markdown--latex--html)
3. [序列化路径：Tiptap JSON → Markdown](#3-序列化路径tiptap-json--markdown)
4. [题目评分逻辑](#4-题目评分逻辑)
5. [AI 上下文集成](#5-ai-上下文集成)
6. [UI 组件调用关系](#6-ui-组件调用关系)
7. [键盘快捷键一览](#7-键盘快捷键一览)

---

## 1. 数据流总览

```
后端 API (/api/quiz/*, /api/banks/*)
     │
     ▼  JSON (Question[])
     │
     ├──→ 渲染路径（用户可见）
     │     MarkdownContent 组件
     │     ├── renderMarkdownHtml()  →  含 LaTeX 预提取的 markdown-it 渲染
     │     └── ⚡ Mermaid 异步渲染（useEffect）
     │
     └──→ 序列化路径（AI 上下文）
           questionsToMarkdown() / notePageToMarkdown()
           └──→ pageContext.markdown  →  AI 助手 system prompt
```

**核心文件：**

| 文件 | 职责 |
|------|------|
| `frontend/src/components/markdown/MarkdownRenderer.tsx` | Markdown 字符串 → 安全 HTML（含 LaTeX + Mermaid） |
| `frontend/src/lib/aiContext.ts` | Tiptap JSON / Question 对象 → Markdown 字符串 |
| `frontend/src/lib/quiz.ts` | 答案标准化与判分 |
| `frontend/src/pages/QuizPage.tsx` | 刷题主界面 |
| `frontend/src/pages/BankPage.tsx` | 题库列表 |
| `frontend/src/pages/WrongPage.tsx` | 错题本 |
| `frontend/src/hooks/useRegisterPageContext.ts` | 注册 AI 上下文（防无限重渲染） |

---

## 2. 渲染路径：Markdown + LaTeX → HTML

### 2.1 MarkdownIt 实例

```ts
const markdown = new MarkdownIt({
  breaks: true,      // 换行符 → <br>
  html: false,       // 禁止原始 HTML
  linkify: true,     // URL 自动转链接
  typographer: true  // 智能引号/省略号
});
```

### 2.2 LaTeX 预提取（`renderMarkdownHtml`）

**正则：**

```ts
const mathPattern = /(?<!\\)\$\$([\s\S]+?)\$\$|(?<!\\)\$([^$\n]+?)\$|(?<!\\)\\\[([\s\S]+?)\\\]|(?<!\\)\\\(([^()\n]+?)\\\)/g;
```

**支持的 4 种 LaTeX 语法：**

| 语法 | 匹配模式 | 显示模式 |
|------|----------|----------|
| `$$...$$` | `$$` 包裹（可跨行） | 块级（display） |
| `$...$` | 单行内 `$` 包裹（不含换行） | 行内（inline） |
| `\[...\]` | `\[` / `\]` 包裹（可跨行） | 块级（display） |
| `\(...\)` | `\(` / `\)` 包裹（不含换行，且**禁止内含括号 `()`**） | 行内（inline） |

**负向后顾 `(?<!\\)`**：防止匹配已转义的 `\$`。

**display / inline 判定：** `$$...$$` 与 `\[...\]` 判为 display；`$...$` 与 `\(...\)` 判为 inline。回填时 display 模式生成的 `<span>` 会额外加 `markdown-math-display` 类名（即 `markdown-math markdown-math-display`），inline 仅 `markdown-math`。

**处理策略——占位符替换（顺序严格）：**

1. 用正则匹配所有 LaTeX 表达式
2. 用 KaTeX `renderToString` 将 LaTeX 转为 HTML（存入 `mathHtml` 数组，display 标志一并保存）
3. 将原文替换为 `DRILL_MATH_PLACEHOLDER_{index}_END`（`{index}` 为无前导零的自然序号，从 0 起）
4. 将占位符文本传给 `markdown.render()` 渲染（避免 markdown-it 误处理 `$`）
5. 对 markdown-it 产物整体执行 `DOMPurify.sanitize()` 消毒
6. 将占位符替换回 KaTeX 生成的 `<span class="markdown-math...">` HTML（用 `html.split(placeholder).join(formula)` 全量替换）

**KaTeX 失败兜底（`renderMath`）：** `renderToString` 设 `throwOnError: false`；若仍抛错，则返回 `<code>{转义后的 latex}</code>`（用 `markdown.utils.escapeHtml` 转义），不静默丢弃，用户可见原始 LaTeX。

### 2.3 安全消毒

双重消毒机制（**串联调用，非分别独立处理同一输入**）：

- **markdown-it 层**：`html: false` 禁止原始 HTML 标签
- **DOMPurify 层**：`DOMPurify.sanitize()` 兜底消毒

实际调用形态为 `DOMPurify.sanitize(markdown.render(source))`——即 markdown-it 先渲染为 HTML 字符串，再对产物整体执行 sanitize。占位符回填的 KaTeX HTML 在 sanitize 之后注入，因此 KaTeX 生成的 `<span>` 不受 DOMPurify 过滤（依赖 markdown-it 的 `html: false` 与 KaTeX 自身输出可控来保证安全）。

### 2.4 Mermaid 图表（客户端异步渲染）

`renderMermaidBlocks(root)` 函数：

1. **`mermaid.initialize` 仅执行一次**（模块级 `mermaidReady` 标志位防重复初始化），配置 `securityLevel: 'strict'`、`theme: 'neutral'`
2. 查找 `pre code.language-mermaid` 元素（markdown-it 渲染的代码块）
3. 调用 `mermaid.render(id, code)` 生成 SVG（`id` 为 `drill-markdown-mermaid-{mermaidId++}`，全局递增）
4. 用 `DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } })` 消毒
5. 用 `<div class="markdown-mermaid">` 替换原元素（`codeBlock.parentElement.replaceWith(wrapper)`，替换的是 `<pre>` 父元素而非 `<code>` 本身）
6. 渲染失败时给 **`codeBlock.parentElement`**（即 `<pre>` 元素）添加 `markdown-mermaid-error` CSS 类——注意是给父元素加类，不是给 `<code>` 本身
7. 通过 `useEffect` 的 cleanup 函数（置 `active = false`）中断未完成的异步渲染，防内存泄漏

### 2.5 MarkdownContent 组件

```tsx
export function MarkdownContent({ value = '', className = '', inline = false }: MarkdownContentProps): JSX.Element {
  const rootRef = useRef<HTMLDivElement>(null);
  const html = renderMarkdownHtml(value);

  useEffect(() => {
    if (!rootRef.current) return undefined;   // 守卫：根元素未挂载时不启动 Mermaid
    return renderMermaidBlocks(rootRef.current);
  }, [html]);  // 仅 html 变化时重渲染

  // inline 模式额外加 markdown-content-inline 类名；className 拼接后 .trim() 清空格
  return <div ref={rootRef} className={`markdown-content ${inline ? 'markdown-content-inline' : ''} ${className}`.trim()} dangerouslySetInnerHTML={{ __html: html }} />;
}
```

**`inline` 模式行为：** 仅切换 CSS 类名（`markdown-content-inline`），不改渲染管线或剥离块级元素；调用方据此用样式控制行内/块级排版。

---

## 3. 序列化路径：Tiptap JSON → Markdown

### 3.1 题目 → Markdown（`questionsToMarkdown`）

```ts
export function questionsToMarkdown(questions: Question[]): string
```

每题格式化为：

```markdown
### 题干内容

A. 选项 A
B. 选项 B
C. 选项 C

**答案：** A

**解析：**

解析内容（可含 Markdown 和 LaTeX）
```

**空值省略规则：** `answer` 为空时整段 `**答案：**` 省略；`analysis` 为空时整段 `**解析：**` 省略（均走 `question.xxx ? ... : ''` 三元判空，非留空占位）。

**空入参容错：** `questions` 为 `null`/`undefined`/空数组时直接返回空串 `''`，不抛错、不输出 `###`。

多题之间用 `---` 分隔（实际拼接为 `'\n\n---\n\n'`）。

### 3.2 笔记内容 → Markdown（`notePageToMarkdown`）

递归遍历 Tiptap JSON 节点树，按节点类型转换。**数据取值方式分两派**：`text` 取 `node.text` 字段；`codeBlock`/`mathInline`/`mathBlock`/`mermaidBlock`/`markdownBlock`/`heading` 等 atomic 节点取 `node.attrs` 字段；其余走 `childNodes()` 递归子节点。

| 节点类型 | 输出格式 | 取值源 | 示例 |
|----------|----------|--------|------|
| `text` | 纯文本 | `node.text` | `"hello"` |
| `hardBreak` | `\n` | — |  |
| `paragraph` | 行内文本（递归 `inlineText`）+ `\n\n` | 子节点 | `一段文字\n\n` |
| `heading` | `#` × level（**clamp 到 1–6，默认 2**）+ 文本 + `\n\n` | `attrs.level` + 子节点 | `## 标题\n\n` |
| `bulletList` | `- item` 逐行（子项走 `inlineText`） | 子节点 | `- 项1\n- 项2\n\n` |
| `orderedList` | `1. item` 逐行（**从 1 起，按数组下标递增**） | 子节点 | `1. 项1\n2. 项2\n\n` |
| `blockquote` | `> text` 逐行（**子项递归 `nodeMarkdown` 处理，支持嵌套**） | 子节点 | `> 引用\n\n` |
| `codeBlock` | `` ```language\ncode\n``` `` | `attrs.language` + 子节点文本 | `` ```js\ncode\n``` `` |
| `mathInline` | `$latex$` | `attrs.latex` | `$E=mc^2$` |
| `mathBlock` | `$$\nlatex\n$$\n\n` | `attrs.latex` |  |
| `mermaidBlock` | `` ```mermaid\ncode\n``` `` | `attrs.code` |  |
| `markdownBlock` | 直接输出原始 Markdown 内容 + `\n\n` | `attrs.markdown` |  |
| `questionBlock` | 调用 `questionMarkdown()` 格式化 + `\n\n` | `attrs.snapshot`（Question 快照） |  |

**未识别类型（`default` 分支）：** 不报错，递归 `nodeMarkdown` 拼接子节点输出。新增节点类型未在 switch 中登记时会被默认透传，不会让整个文档序列化失败。

**最终输出：** `notePageToMarkdown` 对 `nodeMarkdown(page.content)` 产物执行 `.trim()`，去掉首尾空白。

### 3.3 AI 回复插入笔记（`appendMarkdownBlock`）

```ts
export function appendMarkdownBlock(content, markdown): Record<string, unknown>
```

在 Tiptap JSON 文档末尾追加一个 `markdownBlock` 节点（含原始 Markdown 文本），再追加一个空 `paragraph` 节点（方便继续编辑）。

**实现细节：**
- 用展开运算符 `...content` 保留原对象的其他字段（不破坏既有属性）
- `type` 字段若原对象有则保留，否则默认 `'doc'`
- `content.content` 若不是数组则视为空（容错处理，不抛错）
- 新节点顺序：`...existing` → `{ type: 'markdownBlock', attrs: { markdown } }` → `{ type: 'paragraph' }`

---

## 4. 题目评分逻辑

**文件：** `frontend/src/lib/quiz.ts`

### 4.1 答案标准化（`normalizeAnswer`）

```ts
export function normalizeAnswer(value: string | string[]): string
```

处理流程：
1. 字符串按 `,` 分割（或直接使用数组）
2. 每项去空格、转大写
3. **`.filter(Boolean)` 清理空项**（如 `"a,,b"` 的空项被丢弃，而非保留为空字符串）
4. 去重、排序
5. 用 `,` 重新拼接

示例：`"c, a, c"` → `"A,C"`；`"a,,b"` → `"A,B"`

### 4.2 判分（`isAnswerCorrect`）

```ts
export function isAnswerCorrect(type: 'single' | 'multiple', answer: string, expected: string): boolean
```

- **单选题（single）**：忽略大小写和前后空格，直接比较。实现走 `answer.trim().toUpperCase() === expected.trim().toUpperCase()`——**用 `toUpperCase` 而非 `toLowerCase`**，与项目其他地方（如快捷键处理用 `toLowerCase`）方向相反，改这里时注意一致性
- **多选题（multiple）**：双方都经过 `normalizeAnswer` 标准化后比较（无视顺序、去重、清空项）

---

## 5. AI 上下文集成

### 5.1 上下文注册流程

```
页面组件 (QuizPage / BankPage / WrongPage / NotebookPage)
     │
     ├── 构建 pageContext 对象
     │     { kind, title, markdown, route, ... }
     │
     ├── useRegisterPageContext(pageContext)
     │     │
     │     ├── contextKey() 序列化为分隔符拼接的字符串
     │     ├── useMemo 对比 key 是否变化
     │     └── useEffect 写入 Zustand store（防无限重渲染）
     │
     └── AiAssistant 读取 pageContext.markdown
           └── 注入 system prompt:
               "你是学习助手。请结合以下当前页面上下文回答，
                必要时用 Markdown 与 LaTeX。\n\n${contextMarkdown}"
```

### 5.2 各页面上下文内容

| 页面 | `kind` | `markdown` 来源 |
|------|--------|------------------|
| 刷题页 | `quiz` | `questionsToMarkdown([当前题目])`，含正确答案和解析 |
| 题库页 | `bank` | `questionsToMarkdown(当前题库所有题目)` |
| 错题本 | `wrong` | `questionsToMarkdown(所有错题)` |
| 笔记本 | `note` | `notePageToMarkdown(page.content)` |
| 未绑定 | `none` | 空（用户可手动提问） |

**AiAssistant 上下文开关与预览截断：**
- `usePageContext` 复选框（默认勾选）控制是否注入上下文；取消勾选则 `contextMarkdown` 置空，system 消息省略
- 上下文卡片预览框对 `contextMarkdown` 截断至 480 字符，超长显示 `…`——但注入 system prompt 的仍是全文，截断仅作用于 UI 预览
- `pageContext.kind === 'none'` 时标题区显示「未绑定页面（可手动提问）」

### 5.3 防无限重渲染

`useRegisterPageContext` 通过 `contextKey()` 生成一个序列化键，只有键实际变化时才写入 Zustand store：

```ts
function contextKey(context: AiPageContext): string {
  return [
    context.kind,
    context.title,
    context.markdown,
    context.route ?? '',
    context.notePageId ?? '',
    context.notebookId ?? '',
    context.questionId ?? ''
  ].join('\u0001');
}
```

**双保险防重渲染：**
1. `useMemo` 算 key，依赖上述 7 项（含 `?? ''` 容空）——key 不变则引用不变
2. `useEffect` 内再用 `useRef` 存 `lastKey.current`，与 key 比对后才决定是否调 `setPageContext`——即使 effect 因 `context` 引用变化被触发，也会被 lastKey �拦下

**`useEffect` 依赖数组是 `[context, key]`**——即 context 对象引用变化也会触发 effect（内部被 lastKey 拦下），并非纯 key 驱动。这意味着父组件每次重渲染传新对象引用时 effect 都会跑一次，但实际写 store 仍由 lastKey 兜底。

改 `AiPageContext` 字段时须同步更新 `contextKey` 与 `useMemo` 依赖数组，否则新字段不参与去重判定。

---

## 6. UI 组件调用关系

### 6.1 MarkdownContent 使用方

| 组件 | 文件 | 用途 | inline 模式 |
|------|------|------|-------------|
| `AiAssistant` | `AiAssistant.tsx` | 渲染 AI 回复的 Markdown | 否 |
| `MarkdownBlockNode` | `editor/MarkdownBlock.tsx` | 编辑器内 Markdown 块预览 | 否 |
| `QuestionBlock` | `editor/QuestionBlock.tsx` | 题目快照的题干、选项、解析 | 选项用 inline |
| `BankPage` | `pages/BankPage.tsx` | 题库列表中的题目题干和选项 | 选项用 inline |
| `QuizPage` | `pages/QuizPage.tsx` | 刷题界面题干、选项、解析 | 选项用 inline |

### 6.2 刷题交互流程

```
QuizPage 组件
     │
     ├── 选择题库 / 传入 questionIds（错题再练）
     ├── POST /api/quiz/sessions 创建 session
     │
     ├── 显示题目
     │   ├── 题干：<MarkdownContent value={question.stem} />
     │   ├── 选项：遍历 options，<MarkdownContent inline value={option.text} />
     │   └── 键盘数字键快速选择（见 §7，实际跟选项数量走）
     │
     ├── 提交答案
     │   ├── POST /api/quiz/sessions/{id}/submit
     │   ├── 显示对错标记 + 正确选项绿框
     │   └── 解析：<MarkdownContent value={result.analysis} />
     │
     └── 后续操作
         ├── 添加到笔记（POST /api/notes/pages/{id}/questions/{id}）
         └── AI 讲解（打开 AiAssistant，上下文已自动注入当前题目）
```

**快捷键监听边界：** QuizPage 的 `keydown` 监听会先检查 `event.target.tagName`，若目标落在 `INPUT`/`TEXTAREA`/`SELECT`/`BUTTON` 元素上则直接 return，避免在表单输入时误触发选题或提交。

---

## 7. 键盘快捷键一览

**刷题页（QuizPage）：**

| 按键 | 作用 |
|------|------|
| `1`-`N`（N 为选项数量） | 选择对应选项——实际按 `Number(event.key) - 1` 索引取 `options` 数组，选项少于 4 时按 `4` 无效，多于 4 时按 `5`/`6` 也能选 |
| `Enter` / `Ctrl+S` | 提交答案（仅在未提交时） |
| `Enter` / `→` / `PageDown` / `N` | 下一题（仅在已提交后）——**`Enter` 是状态依赖二态键**：未提交时为提交，提交后为下一题 |
| `←` / `PageUp` / `P` | 上一题 |

**全局快捷键（AiAssistant 监听，非 QuizPage）：**

| 按键 | 作用 |
|------|------|
| `Ctrl+J` | 切换 AI 助手开合——此键由 `AiAssistant` 组件独立监听 `toggleAi`，**不在 QuizPage 的 keydown 处理分支内**，全局可用 |

**Markdown 块编辑器（MarkdownBlock）：**

| 按键 | 作用 |
|------|------|
| `Escape` | 取消编辑 |
| `Ctrl+Enter` / `Cmd+Enter` | 完成编辑 |