# 会话内短周期记忆曲线（循环背诵）

本文档描述 Drill Notebook 的**会话内（短周期）记忆曲线**：一次背诵会话中的「多轮循环出场 + 错题重复」模型、配置项、队列算法与界面交互。与跨天间隔重复（SM-2 风格，`review_schedule` 表）相互独立，详见 [docs/review-srs.md](review-srs.md)。

前端实现：

- 队列算法与配置：`frontend/src/lib/sessionCurve.ts`
- 背诵设置弹窗：`frontend/src/components/SessionCurveSettingsModal.tsx`
- 背题会话：`frontend/src/pages/QuestionStudyPage.tsx`（练习 → 背诵 → 背题）
- 背知识点选材与会话：`frontend/src/pages/knowledge/KnowledgeMemorizePanel.tsx`、`KnowledgeMemorizeSession.tsx`（练习 → 背诵 → 背知识点）
- 全局默认值：设置页「会话内记忆曲线」卡片（`SettingsPage.tsx`）与背诵设置弹窗共用同一份 localStorage 配置
- 会话结束排程与顽固项加练：`frontend/src/components/SessionPlanRecommendModal.tsx`
- 实时「今天」：`frontend/src/lib/useToday.ts`（`CalendarPage.tsx` 消费）

---

## 1. 概念模型

借鉴百词斩的背单词节奏，在一次背诵会话内完成短周期重复：

- **循环轮数（loops）**：所选条目每轮按既定顺序全部出场一次，共循环 R 轮（默认 3）。对应「把选择的题循环出现三遍」。
- **错题重复（组末复习，默认）**：每轮按 `groupSize`（默认 10）题分组，答错的条目插入**本组末尾**集中重现；可切换为「本轮末尾」或「延迟重现（隔 gap 题）」。
- **额外重复上限（maxRepeats）**：基线轮次之外，单条最多额外重现的次数；0 = 不限（直到会为止）。到限仍未过关标记「本次未记住」，**后续轮次的基线出场仍保留**。
- **过关条件（passStreak）**：连续答对多少次算过关。开启 `skipPassed` 后，过关条目从后续轮次移除（默认关闭：每轮完整循环）。
- **次轮顺序（nextRoundOrder）**：进入下一轮时，未过关条目可选「排到轮首（错题优先）」或「随机」；默认保持原序。

答对/答错同时写入跨天曲线（会 = quality 4，不会 = quality 0）。**以会话最终判定为准**：同一条目首次评分即提交，之后只有当「会/不会」判定翻转时才再补交一次（带 `forceAdvance`），判定不变就不再重复提交——多轮循环不会把一张已判定的卡在同一天反复推远。详见第 5 节。

## 2. 配置项（localStorage `session.curveConfig`）

| 字段 | 类型/范围 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | boolean | `true` | 总开关；关闭后单轮顺序过一遍，答错不重现 |
| `loops` | 1–10 | `3` | 循环轮数 |
| `strategy` | `group` / `tail` / `gap` | `group` | 错题重现插入策略（组末复习 / 本轮末尾 / 延迟重现） |
| `groupSize` | 2–100 | `10` | `group` 策略的每组题数 |
| `gap` | 1–50 | `3` | `gap` 策略的延迟条目数 |
| `maxRepeats` | 0–999 | `3` | 单条最大额外重现次数；0 = 不限 |
| `passStreak` | 1–10 | `1` | 连对多少次算过关 |
| `skipPassed` | boolean | `false` | 过关后跳过后续轮次 |
| `nextRoundOrder` | `original` / `wrongFirst` / `random` | `original` | 下一轮出场顺序 |

读取时经 `normalizeSessionCurveConfig` 钳位/回退，兼容旧版本字段（缺字段取默认，越界钳位）。

**预设方案**（弹窗一键应用）：

| 预设 | 配置要点 |
|---|---|
| 快速过一遍 | loops=1，strategy=gap |
| 推荐 · 三轮循环 | loops=3，组末复习 |
| 百词斩式 | loops=3，组末复习，**次轮错题优先** |
| 强化 · 五轮循环 | loops=5，maxRepeats=5，次轮错题优先 |

## 3. 队列算法（`sessionCurve.ts`）

### 3.1 数据结构

```ts
CurveEntry { entryId, resourceId, attempt, round }  // attempt=本轮内额外重现序号；round=轮次(0起)
CurveItemState { streak, repeats, done, abandoned, lastRatedEntryId?, lastRoundWrong }
```

- `buildCurveQueue(ids, loops)`：预构建 `ids × loops` 的基线队列（按轮非降序排列）。
- `lastRatedEntryId`：UI 据此判断「**当前这一条**是否已评分」。多轮循环下同一资源每轮都是新的 entry，可逐轮重新评分（不能用资源级 `done` 判断）。

### 3.2 作答推进（`applyCurveAnswer`）

- **答对**：`streak+1`；达到 `passStreak` → 过关，`skipPassed` 时移除该条目当前位置之后的所有出场；连对不足 → 本轮内继续排入巩固（受 `maxRepeats` 封顶）。
- **答错**：`streak` 清零、`lastRoundWrong=true`；`repeats < maxRepeats`（或不限）→ 追加重现条目：
  - `group`：插入所在组末尾（基线条目按 groupSize 分段定位边界）；
  - `tail`：插入本轮末尾；
  - `gap`：`min(当前位置+1+gap, 本轮末尾)`；
  - 最后一轮时「本轮末尾」即整队末尾（插入背诵清单末尾继续重复）。
  - 超上限 → `done + abandoned`，不再额外重复。
- **离开本轮时**（本轮再无可出场条目）：若 `nextRoundOrder ≠ original`，对下一轮的基线块做「错题优先」稳定排序或洗牌。

### 3.3 兼容边界

- `loops = 1` 且 `strategy = gap` 时与旧版单轮行为完全一致。
- **刷题（QuizPage）不受新默认影响**：固定注入 `{ loops: 1, strategy: 'gap', nextRoundOrder: 'original' }`。
- **日历 / 今日队列 / 深链会话**（`/knowledge?pointIds=…`、`/memorize?questionIds=…`）强制 `singleLoop`：定时任务只安排一次出场，不会循环多遍。

## 4. 页面与交互

### 4.1 练习页结构

侧栏「练习」两个 Tab：**刷题** ｜ **背诵**；背诵内子切换「背题（题库）｜ 背知识点」。

### 4.2 背题（练习 → 背诵 → 背题）

1. 选择题库 → `AdvancedQuestionSelector` 勾选并编排顺序（支持筛选、全选/取消筛选结果、随机重排）。
2. 「开始背题」旁的 **「记忆曲线 · 摘要」** 按钮打开设置弹窗（预设 + 全部自定义项 + 出场规模预估），保存写 localStorage，下次开始会话生效。
3. 会话中：进度条显示「第 n / N 题」与蓝色「第 r / R 轮」标签、橙色「第 k 遍」重现标签；题号跳转面板按轮分组，重现条目橙色虚线。

### 4.3 背知识点（练习 → 背诵 → 背知识点）

- 专用轻量界面，不再复用知识点阅读页：左侧知识树级联勾选（勾父带子，节点带子树计数），右侧摘要 + 曲线设置 + 开始。
- **只背叶子卡片**：父节点/章节节点仅圈定范围，不进入背诵清单；计数与开始按钮均为叶子数。
- 背诵顺序 = 文档树顺序。「知识点」阅读页保持纯阅读；其深链会话（日历进入）仍工作正常。

### 4.4 题库 → 刷题选题继承

题库页勾选部分题目后点「开始练习」，跳转 `/quiz?bankId=X&questionIds=…&from=bank`；刷题页显示「已选题目：N 题（可继续调整）」并保留选题器供增减；未勾选时仍按整库进入。错题页「错题再练」深链行为不变。

## 5. 会话 ↔ 日历 / 跨天曲线联动

短周期会话的结论要落到长周期安排上，才有「今天错的高频题，明天日历里再见」。三块联动都是**前端策略 + 既有后端接口**，后端零改动。

### 5.1 跨天评分以会话最终态为准

- 会话内维护 `submittedRatings: Map<resourceId, known>`：首次评分正常提交；之后**只有判定翻转**才再提交一次，并带上 `forceAdvance: true`。
- `forceAdvance` 绕过后端「同一天已有主推进 → 只记 extra 练习、不动 `next_review`」的策略（`CompletionSyncService.onItemCompleted`），于是「先判不会、后来又判会」的卡片当天真的被推远；反之重复答对不会反复推远。
- 判「会」后 `ensureNextReviewAfterViewDay` 保证卡片离开当天队列（含查看未来日期的补做场景）。
- 来自日历 / 今日队列的会话（`fromQueue`）本就按补做语义恒定 `forceAdvance`。

### 5.2 顽固项自动加练（会话结束弹窗内）

| 环节 | 行为 |
|---|---|
| 识别 | `deriveStubbornIds(states)`：`abandoned`（重复用尽仍未过关）或 `repeats ≥ STUBBORN_REPEAT_THRESHOLD(2)`，对应 Anki 的 leech、百词斩的「顽固错词」 |
| 呈现 | 「本轮结束后的学习计划」弹窗底部的 **顽固项加练（N）** 卡片，**默认勾选**，展示条目名（最多 6 条，超出显示「等 N 项」） |
| 排程 | 额外生成 2 个计划组「顽固项加练 · 第 1 天 / 第 2 天」= 明天、后天各一轮，独立于用户所选窗口，不受均分影响 |
| 写入 | 与常规候选合并成一次 `/api/study-plans/recommend/session-apply` 提交；顽固项即使不在常规候选里，也会并入 `enroll` 全集（按 `资源类型:id` 去重） |
| 边界 | 取消勾选 → 不产出加练组；只有顽固项时不再写空条目的常规组；「加入学习日历」关闭时卡片内提示排程不会写入；开启 AI 排程时 AI 分组 + 顽固组一并提交 |

背题、背知识点两条会话共用同一套逻辑（`SessionPlanRecommendModal`），日历与今日队列随后即可看到这两天的加练条目。

### 5.3 日历实时「今天」

- `useToday()`（`frontend/src/lib/useToday.ts`）：每 60 秒对齐系统日期，窗口重新聚焦时立即校准一次。
- `CalendarPage` 用它替代原先渲染期一次性取的 `todayYmd()`——今天高亮、逾期红点、`?date=` 是否省略都跟手。
- 跨零点：真实日期前进时失效 `study-plans` / `study-plans-day` / `review-calendar-stats` / `study-today` 缓存；若用户正停留在旧的今天，选中日自动跟随到新的一天；手动翻看其它日期时只刷新数据、不跳转。

## 6. 测试

| 自动化位置 | 覆盖 |
|---|---|
| `frontend/src/lib/sessionCurve.test.ts`（20 用例） | 多轮队列构建、三种插入策略与轮边界、组末定位、重复上限与放弃、连对过关、skipPassed 剪枝、次轮错题优先、逐轮重评、配置钳位、摘要文案、**顽固项阈值与真实会话推导** |
| `frontend/src/components/SessionCurveSettingsModal.test.tsx`（3 用例） | 弹窗渲染、预设应用与保存落库、每次打开重读最新配置 |
| `frontend/src/components/SessionPlanRecommendModal.stubborn.test.tsx`（4 用例） | 加练卡片默认勾选、提交明天+后天两组并去重并入 enroll 全集、取消勾选后禁止提交、无顽固项不渲染 |
| `frontend/src/pages/knowledge/KnowledgeMemorizeSession.test.tsx`（3 用例） | 首次评分提交、判定翻转才补交（`forceAdvance`）、多轮重复同判定不再提交 |
| `frontend/src/lib/useToday.test.tsx`（2 用例） | 轮询跨零点变更日期、窗口聚焦立即校准 |
| `frontend/src/pages/CalendarPage.midnight.test.tsx`（3 用例） | 打开时高亮今天并加载其队列、跨零点选中日跟随并重新拉数、手动选中其他日期时不被跳转 |
| `frontend/src/pages/BankPage.workspace.test.tsx` BNK-16/16b | 开始练习跳转：无勾选仅 bankId；有勾选携带 questionIds+from=bank |
