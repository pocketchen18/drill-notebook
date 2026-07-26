# Drill Notebook 架构说明

当前仓库实现层面的架构边界；若与代码冲突，以代码为准。

## 1. 进程与通信

| 进程 | 技术 | 职责 |
|---|---|---|
| Electron Main | Node | 窗口、菜单、便携路径、单实例、spawn Java、生命周期 |
| Renderer | React 18 + TS + Vite + Arco | 全部 UI；通过 `window.api` 与主进程交互；HTTP 访问后端 |
| Backend | Spring Boot + SQLite | 领域 API：题库、练习会话、导入导出辅助、计划、SRS、AI 代理与会话 |

- 后端绑定 **`127.0.0.1`**，端口由启动参数/协商文件决定（见 `electron/java-bridge.ts`）。  
- 渲染进程 **不直连** 用户 AI 厂商密钥所在的业务逻辑：由后端代发。  

## 2. 前端分层

```
pages/          路由页面（题库、刷题、背题、知识点、错题、日历、笔记、设置）
components/     复用 UI（AI 侧栏、导出、高级选题、编辑器、计划弹窗…）
stores/         Zustand（主题、AI 开关、页面上下文、复习会话等）
lib/            API 客户端、导入导出、复习/计划算法、工具
hooks/          如 useRegisterPageContext（避免 setState 死循环）
styles/app.css  设计 token + 组件样式 + 深色模式
```

### 2.1 主题

1. `uiStore.theme`: `'light' | 'dark'`  
2. `html[data-theme]`：CSS 变量（`--text`、`--panel-bg`…）  
3. `body[arco-theme=dark]`：Arco 组件库暗色  
4. `localStorage['drill-notebook-theme']`：首屏兜底  
5. Electron `config`：跨会话持久化  

Drawer/Modal 等 Portal 挂到 `#root` 或依赖 `html` 级变量 + `arco-theme`，避免「侧栏不跟主题」。

### 2.2 AI 页面上下文

学习页通过 `useRegisterPageContext` 注册 `{ kind, title, markdown, ... }`。  
仅内容变化时写入 store，防止 React #185 最大更新深度错误。

## 3. 后端领域（概念）

| 域 | 内容 |
|---|---|
| 题库 | bank / question / FTS / 导入哈希 |
| 练习 | quiz session、answer_record、错题查询 |
| 笔记 | notebook / note_page / 题目快照块 |
| 计划 | 日历计划组与条目（题目/知识点/笔记） |
| 复习 | spaced repetition 配置、enrollment、schedule、log |
| AI | 配置密文、多会话、消息密文、chat/summarize 代理 |

Schema 以 `backend/src/main/resources/schema.sql` 为准。

## 4. 导入 / 导出

- **导入**：Markdown 解析器、PDF 服务（规则 + AI 兜底）、JSON 等，见 `docs/import-formats.md`。  
- **导出**：前端 `lib/export.ts` + 页面 `ExportActions`；题库/笔记/错题/会话等。  

## 5. 便携与打包

- 开发：未打包时默认在工作区旁建立便携数据根（`APP_ROOT`，不入库）。  
- 生产：`electron/paths.ts` 将 userData/cache/temp 指到可执行文件旁。  
- 打包：`docs/jlink.md` + `npm run package:portable`。  

## 6. 测试

- 前端：Vitest（`frontend` 内 `*.test.ts`）。  
- 后端：JUnit（`backend/src/test`）。  
- 脚本：`scripts/smoke-mvp.ps1`、`scripts/portable-audit.ps1`。  

## 7. 版本演进（git 摘要）

| 阶段 | 主题 |
|---|---|
| MVP / v0.1–0.2 | 壳 + 题库刷题笔记 AI 基础 + 便携 |
| v0.3 | PDF/导出/高级挑题/知识点导入增强 |
| v0.4 | SM-2 复习、日历计划、今日队列、AI 多会话 |

更细提交说明用：`git log --oneline`。
