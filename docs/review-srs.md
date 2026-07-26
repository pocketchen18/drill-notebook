# 间隔重复复习（SM-2 风格）

本文档描述 Drill Notebook 的**间隔重复（Spaced Repetiation）复习**模块：数据库结构、HTTP API、复习配置（`spaced_repetition_config`）、今日队列与日历叠加。

对应后端实现：

- 模型：`ReviewSchedule` / `ReviewLog` / `SpacedRepetitionConfig`
- 仓储：`ReviewRepository`
- 服务：`ReviewService` / `ReviewScheduler` / `ReviewScheduleApplier` / `TodayQueueService` / `CompletionSyncService`
- 控制器：`ReviewController`（`/api/review/**`）、`StudyController`（`/api/study/**`）

前端入口：

- 「复习配置」页 `frontend/src/pages/ReviewConfigPage.tsx`
- 「设置 → 复习方案」`frontend/src/pages/SettingsPage.tsx`
- 「日历 → 今日队列」`frontend/src/components/TodayQueuePanel.tsx`
- 客户端封装 `frontend/src/lib/review.ts`

---

## 1. 算法概览

`ReviewScheduler` 实现 SM-2 风格的间隔重复：

- **quality**：0–5 的回忆质量评分（0 = 完全忘了，5 = 太简单了）。
- **EF（Ease Factor）**：难度系数，初始 `2.5`，最低 `1.3`。每次通过后按
  `EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))` 更新。
- **repetitions**：连续通过次数；答错（quality < 3）后归零。
- **interval（天）**：
  - quality ≥ 3：查 `spaced_repetition_config.intervals` 中第 `repetitions + 1` 阶段的间隔；
    若配置未给出，则按 SM-2 默认：第 1 次 = 1 天，第 2 次 = 6 天，之后 `上一间隔 × EF`。
  - quality < 3：按 `wrong_strategy` 处理（间隔减半 / 重置到 `wrong_fixed_days` / 减少 25% / 固定天数）。
- **status**：`new` → `learning` → `review` → `mastered`，由 `interval` 与 `repetitions` 推导：
  - `interval ≥ 90 且 repetitions ≥ 5` → `mastered`
  - `interval ≥ 21` → `review`
  - `repetitions > 0` → `learning`
  - 其他 → `new`
- interval 同时受 `minimum_ef`、`max_interval_days` 与配置中最小间隔的钳制。

每次提交写一条 `review_log`，并更新对应 `review_schedule` 行的 `ef` / `interval` / `repetitions` / `next_review` / `status` 等字段。

---

## 2. 数据库结构（`schema.sql`）

### 2.1 `spaced_repetition_config` — 复习配置方案

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK | 自增主键 |
| `name` | TEXT UNIQUE | 方案名称 |
| `is_default` | INTEGER | 是否默认（0/1） |
| `intervals_json` | TEXT | 第 N 次通过后的间隔天数，JSON map，如 `{"1":1,"2":6,"3":16}` |
| `initial_ef` | REAL | 初始 EF |
| `minimum_ef` | REAL | EF 下限 |
| `max_interval_days` | INTEGER | 单次最大间隔（天） |
| `wrong_strategy` | TEXT | `reset` / `reduce_half` / `reduce_quarter` / `fixed` |
| `wrong_fixed_days` | REAL | `reset` / `fixed` 时使用的固定天数 |
| `daily_new_limit` | INTEGER | 每日新学上限 |
| `daily_review_limit` | INTEGER | 每日复习上限 |
| `priority_mode` | TEXT | `due_first` / `worst_first` / `random` / `mixed` |
| `created_at` | TEXT | 创建时间 |

预置三条记录：`标准模式`（默认）、`考前突击`、`保守学习`。新建/更新默认方案时会自动取消其他方案的默认标记。

### 2.2 `review_schedule` — 每个条目的复习状态

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK | 自增主键 |
| `item_type` | TEXT | `question` 或 `knowledge_point` |
| `item_id` | INTEGER | 关联资源 id |
| `config_id` | INTEGER | 关联 `spaced_repetition_config.id` |
| `ef` / `interval` / `repetitions` | REAL/REAL/INTEGER | 当前 SM-2 状态 |
| `next_review` | TEXT | 下次到期时间 `yyyy-MM-dd HH:mm:ss` |
| `last_review` | TEXT | 上次复习时间 |
| `last_quality` | INTEGER | 上一次评分 |
| `total_reviews` / `total_wrong` | INTEGER | 累计复习 / 答错次数 |
| `streak_correct` | INTEGER | 当前连续正确次数 |
| `status` | TEXT | `new` / `learning` / `review` / `mastered` |
| `created_at` / `updated_at` | TEXT | 时间戳 |

唯一约束 `UNIQUE(item_type, item_id, config_id)`：同一资源在同一方案下只能存在一条 schedule。

索引：`idx_review_schedule_due(item_type, next_review)`、`idx_review_schedule_item(item_type, item_id)`。

### 2.3 `review_log` — 每次复习的流水

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK | 自增主键 |
| `schedule_id` | INTEGER | 关联 `review_schedule.id`，`ON DELETE CASCADE` |
| `quality` | INTEGER | 本次评分 |
| `response_time` | INTEGER | 答题耗时（秒），可空 |
| `scheduled_interval` | REAL | 本次评分前已排定的间隔 |
| `actual_interval` | REAL | 距上次复习的实际间隔 |
| `source` | TEXT | 来源，默认 `manual`（如 `quiz`、`plan_complete` 等） |
| `reviewed_at` | TEXT | 复习时间 |

索引：`idx_review_log_schedule(schedule_id, reviewed_at DESC)`。

---

## 3. HTTP API

所有接口挂在后端 Spring Boot 服务上，默认仅监听 `127.0.0.1`。

### 3.1 配置管理

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/review/configs` | 列出全部方案（默认方案排前） |
| GET | `/api/review/configs/{id}` | 获取单个方案 |
| POST | `/api/review/configs` | 新建方案，返回 `{id, ok}` |
| PUT | `/api/review/configs/{id}` | 更新方案 |
| DELETE | `/api/review/configs/{id}` | 删除方案（默认方案不可删） |

### 3.2 入群 / 退群 / 提交

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/review/enroll` | 把题目/知识点加入某方案；已存在则返回 `already_enrolled` |
| POST | `/api/review/unenroll` | 把条目移出复习方案 |
| POST | `/api/review/submit` | 提交一次评分 `{scheduleId, quality, responseTime?, source?}`，返回更新后的 schedule 状态 |
| POST | `/api/review/reset/{type}/{id}` | 重置某条目的复习进度 |

### 3.3 查询

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/review/due` | 列出活跃 schedule（含 `new`/`learning`/`review`/`mastered`）；带题目快照 |
| GET | `/api/review/stats` | 统计：totalEnrolled / newCount / learningCount / reviewCount / masteredCount / dueToday / newToday / dailyStats |
| GET | `/api/review/schedule/{type}/{id}` | 查看某条目的 schedule 与最近 20 条 log |
| GET | `/api/review/calendar-stats?from=YYYY-MM-DD&to=YYYY-MM-DD` | 日历叠加：按天返回 SRS 到期数与逾期数 |

### 3.4 今日队列 / 完成同步

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/study/today?date=YYYY-MM-DD&configId=...` | 今日队列：合并「已加入复习方案且今日到期」与「当日计划待办」 |
| POST | `/api/study/complete` | 计划完成同步：把一个 plan item 标记为完成，必要时联动 SRS |

---

## 4. 复习配置项详解（`ReviewConfigPage` / `SettingsPage`）

- **方案名称**：唯一。
- **设为默认方案**：勾选后会取消其他方案的默认标记。
- **间隔配置（JSON）**：键为「第 N 次通过」的字符串序号，值为间隔天数；缺省时按 SM-2 默认推导。
- **初始 / 最低难度系数（EF）**：典型值 `2.5` / `1.3`。
- **最大间隔（天）**：单次排期上限，默认 `365`。
- **答错后策略**：
  - `reduce_half`（推荐）：当前间隔减半，但不低于配置最小间隔。
  - `reset`：重置到 `wrong_fixed_days` 天。
  - `reduce_quarter`：减少 25%。
  - `fixed`：固定排到 `wrong_fixed_days` 天后。
- **每日新学 / 每日复习上限**：用于截断今日队列中的 `new` / 复习条目。
- **排序策略**：
  - `due_first`：到期优先。
  - `worst_first`：最不熟（EF 最低、连续正确最少）优先。
  - `random`：随机顺序。
  - `mixed`：新旧混合。

---

## 5. 今日队列与日历叠加

### 5.1 今日队列（`TodayQueueService`）

`GET /api/study/today` 返回一个合并队列：

- **curve**：已加入复习方案且今日到期（或逾期）的条目，来自 `review_schedule`。
- **plan**：当日计划待办，来自 `study_plan_item`。
- 同时属于两边的条目会被合并为 `plan_and_due`，避免重复。

每条记录包含 `resourceType`、`resourceId`、`kind`（`due` / `plan` / `plan_and_due`）、`scheduleId`、`srsStatus`、`due`、`overdue` 等字段，前端 `TodayQueuePanel` 据此渲染来源标签与紧急度颜色。

队列前端还维护一个「日队列会话」（`frontend/src/lib/dayQueueSession.ts`，键 `drill.dayQueueSession`），记录当日 step 列表与游标，刷新或重启后可恢复进度。

### 5.2 日历叠加（`ReviewService.calendarStats`）

`GET /api/review/calendar-stats?from=...&to=...` 返回：

- `due`：按天统计在区间内到期的 SRS 条目数。
- `overdue`：按天统计相对真实今天已逾期的条目数。
- `realToday`：服务器当前日期，用于前端区分「今天之前都算逾期」。

日历页据此在每一天单元格上叠加到期/逾期标记，与手动计划条目（`study_plan_item`）独立展示。

---

## 6. 与既有学习流程的联动

- **刷题自动提交**：`ReviewService.autoSubmitFromQuiz` 在题目已加入复习方案时，按答题正确性与耗时自动提交一次评分；未加入则跳过，不会自动入群。
- **计划完成联动**：`CompletionSyncService` 在计划项完成时调用 `review.submit`，把对应 schedule 推进一阶。
- **会话推荐**：刷题 / 背题 / 背知识点结束时弹出的「本轮结束后的学习计划」对话框可把候选条目写入日历，也可同时勾选加入复习方案。

---

## 7. 测试与验证

相关自动化测试：

- `ReviewRepositorySyncTest` — 仓储层的 schedule / log 读写一致性。
- `ReviewServiceWireTest` — 服务层 enroll / submit / stats 的端到端走查。
- `CompletionSyncServiceTest` — 计划完成与 SRS 推进的联动。
- `TodayQueueServiceTest` — 今日队列合并、截断与排序。
- `frontend/src/components/TodayQueuePanel.urgency.test.ts` — 队列紧急度颜色判定。
- `frontend/src/lib/dayQueueSession.test.ts` — 日队列会话的构建与去重。

后端冒烟脚本 `scripts/mvp-test.ps1` 与 `scripts/smoke-mvp.ps1` 会启动后端并打几个核心接口，可用于本地回归。
