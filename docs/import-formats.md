# 题库导入格式说明

本文档规定 PDF 和 JSON 题库导入功能接受的输入格式，对应后端 `PdfImportService` / `PdfTextAdapter` / `JsonQuestionParser` 的实际解析逻辑。前端入口在「题库」页顶部三个按钮：**导入 PDF** / **导入 Markdown** / **导入 JSON**。

## 目录

1. [PDF 导入格式](#1-pdf-导入格式)
2. [JSON 导入格式](#2-json-导入格式)
3. [实操选择建议](#3-实操选择建议)
4. [必死的反例](#4-必死的反例)

---

## 1. PDF 导入格式

PDF 导入走三步链路：

```
PDF 字节
  │  PdfTextExtractor（Apache PDFBox 提文本）
  ▼
原始文本
  │  PdfTextAdapter（规整成 Markdown）
  ▼
规整 Markdown
  │  MarkdownQuestionParser（解析为题目）
  ▼
入库
```

关键不是 PDF 里看着怎样，而是 **PDFBox 提出来的纯文本**要长得像下面三种版式之一。

### 1.1 版式 Y：题答案一体（最稳，推荐）

```
1. Java 中用于定义类的关键字是什么？
A. function
B. class
C. struct
D. type
答案：B
解析：Java 使用 class 关键字声明类。

2. 以下哪些属于 Java 集合接口？
A. List
B. Thread
C. Set
D. String
答案：A、C
解析：List 和 Set 都是 Java 集合框架中的接口。
```

#### 硬要求

| 元素 | 正则 | 示例 |
|---|---|---|
| 题号开头 | `^\s*(\d+)[.、．]\s*` | `1.` / `1、` / `1．` |
| 选项每行独占 | `^\s*([A-Da-d])[.、\)）]\s*(.*)$` | `A. function` / `A) function` / `A、function` |
| 答案标记 | 多种变体 | `答案：B` / `正确答案：B` / `答：B` / `【答案】B` |
| 多选答案 | 顿号或逗号分隔 | `答案：A、C` 或 `答案：A,C` |
| 解析（可选） | 含"解析"或"【解析】"或"【分析】" | `解析：...` |

### 1.2 版式 X：题答案分离（也支持）

```
一、单选题
1. Java 中用于定义类的关键字是什么？
A. function
B. class
C. struct
D. type

2. 创建 Java 对象时必须使用的关键字是
A. class
B. new
C. create
D. static

参考答案
1. B  Java 使用 class 关键字声明类。
2. B  new 关键字用于在堆内存为对象分配空间并初始化。
```

题在前、参考答案在后成独立章节。章节标题要能被认出：`参考答案` / `答案及解析` / `答案与解析` / `参考答案及解析`（不区分大小写）。

### 1.3 版式 Z：混合（也支持）

部分题答案跟题、部分题分离到参考答案章节——版式 Y 和 X 的混排。

### 1.4 题型推断规则

| 信号 | 推断题型 |
|---|---|
| 有 ≥2 个选项 + 单字母答案 | `single` 单选 |
| 有 ≥2 个选项 + 多字母答案（`A、C` 或 `A,C`） | `multiple` 多选 |
| 无选项 + 答案是 `true/false/对/错/是/否/T/F` | `true_false` 判断 |
| 无选项 + 有答案 | `fill` 填空 |
| 无选项 + 无答案 | `essay` 解答 |

### 1.5 阈值——决定走规则还是 AI 兜底

`PdfTextAdapter.adapt()` 返回 `AdapterResult`，含置信度：

```
confidence = (completeFrontmatter/total + validOptions/total) / 2
```

`PdfImportService` 的阈值：

- `totalQuestions < 3`（题数不够）→ 回退 AI
- `confidence < 0.5`（置信度过低）→ 回退 AI
- AI 也没配 → 抛错
- **环回 Markdown 残迹**（含 `===` 分隔符 + 裸 `type:` frontmatter 行）会明确提示「此 PDF 的版式需 AI 解析但未配置 AI API Key，请在设置中配置后再试」

### 1.6 参考活模板

`backend/src/test/resources/fixtures/pdf/sample-for-import-test.pdf` 是按版式 Y 造的活模板——题号 `1./2./3./4.` 开头、选项每行独占、`答案：`、`解析：`——实测走规则路径 4 题全解析活（single / multiple / fill / true_false 四种题型全到位），可作为 PDF 格式的参照样本。

---

## 2. JSON 导入格式

走 `JsonQuestionParser`。两种顶层结构都接。

### 2.1 结构 A：对象包 questions 数组

```json
{
  "questions": [
    {
      "type": "single",
      "stem": "Java 中用于定义类的的关键字是什么？",
      "options": [
        {"key": "A", "text": "function"},
        {"key": "B", "text": "class"},
        {"key": "C", "text": "struct"},
        {"key": "D", "text": "type"}
      ],
      "answer": "B",
      "analysis": "Java 使用 class 关键字声明类。",
      "difficulty": 2,
      "tags": ["java", "basics"],
      "chapter": "Java 基础"
    },
    {
      "type": "multiple",
      "stem": "以下哪些属于 Java 集合接口？",
      "options": [
        {"key": "A", "text": "List"},
        {"key": "B", "text": "Thread"},
        {"key": "C", "text": "Set"},
        {"key": "D", "text": "String"}
      ],
      "answer": "A,C",
      "analysis": "List 和 Set 都是 Java 集合框架中的接口。"
    },
    {
      "type": "fill",
      "stem": "JVM 的英文全称是什么？",
      "answer": "Java Virtual Machine"
    },
    {
      "type": "true_false",
      "stem": "Java 字节码可以由 JVM 执行。",
      "answer": "true"
    },
    {
      "type": "essay",
      "stem": "简述垃圾回收的目标。"
    }
  ]
}
```

### 2.2 结构 B：纯数组

```json
[
  {"type": "single", "stem": "...", "options": [...], "answer": "B"},
  {"type": "multiple", "stem": "...", "options": [...], "answer": "A,C"}
]
```

### 2.3 字段对照表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | `single` / `multiple` / `fill` / `true_false` / `essay` 五选一 |
| `stem` | string | ✅ | 题干，不能为空 |
| `options` | array | 选择题必填 | `[{"key":"A","text":"..."}, ...]`，至少 2 项 |
| `answer` | string | 除 essay 外必填 | single 单字母 `B`；multiple 逗号分隔 `A,C`；true_false `true`/`false`；fill 文本 |
| `analysis` | string | 可选 | 解析 |
| `difficulty` | int | 可选，默认 3 | 1-5 整数，越界报错 |
| `tags` | array | 可选 | `["java","basics"]` |
| `chapter` | string | 可选 | 章节 |
| `groupId` | string | 可选 | 组 ID |
| `orderInGroup` | int | 可选 | 组内顺序 |

### 2.4 校验红线（报错就整题跳过）

- `type` 不在五种里 → 「题型必须是 single、multiple、fill、true_false 或 essay」
- `stem` 空 → 「第 N 题题干不能为空」
- 选择题 `options` 不是数组或 <2 项 → 「选择题至少需要两个选项」
- `options` 元素不是对象或缺 `key`/`text` → 「第 N 题的选项格式不合法」
- `difficulty` 不是 1-5 整数 → 「第 N �题难度必须是 1 到 5 的整数」
- 选择题没 `answer` → 校验失败
- JSON 本身不合法 → 「JSON 格式不合法：...」

### 2.5 字段名大小写注意

JSON 用 **camelCase**：`groupId`、`orderInGroup`（不是 `group_id`、`order_in_group`）。这是 `JsonQuestionParser` 写死的字段名。

PDF 里反过来——`PdfTextAdapter` 输出的规整 Markdown frontmatter 用 **snake_case**：`group_id`、`order_in_group`。但这只在 Adapter 内部转 Markdown 时用，造 PDF 时不用管这层，只管题号 + 选项 + 答案标记。

---

## 3. 实操选择建议

| 来源 | 选什么格式 | 为什么 |
|---|---|---|
| 手抄题库 | JSON | 结构严，校验细，错了能精确定位是第几题什么字段 |
| 题目 PDF 是规整题目版式 | PDF | 走规则路径，无需配 AI |
| 题目 PDF 是「规整 Markdown 转 PDF」的环回产物 | PDF + 必须配 AI | 规则救不活，AI 兜底才行 |
| 题目 PDF 中文乱码严重 | JSON | PDFBox 提不出干净文本，规则和 AI 都会受影响 |
| 题目本来就是 Markdown | 不要走 PDF | 直接用「导入 Markdown」入口，绕开 PDFBox 提取这一层风险 |

---

## 4. 必死的反例

### 4.1 环回 Markdown 转 PDF

「规整 Markdown → PDF → 再提取」的环回文本——题号丢了、`---` 围栏丢了、选项挤在一行 `A. 选项 B. 选项 C. 选项 D. 选项`、`===` 分隔符位置错位。这种 PDF 走规则路径**必败**，要么配 AI，要么别这么造。

### 4.2 PDF 中文乱码严重

PDFBox 提不出干净文本（大量 `?` 替代中文），规则和 AI 都会受影响——改用 JSON。

### 4.3 选项挤行

题干正文后直接接 `A. 选项 B. 选项 C. 选项 D. 选项` 挤在一行——`MarkdownQuestionParser` 的选项正则要求每行只放一个选项且行首匹配，挤行认不出。要么每行独占一个选项，要么走 AI。
