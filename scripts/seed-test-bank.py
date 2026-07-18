"""测试题库生成脚本 —— 验证记忆曲线功能。

用法：启动应用后运行此脚本
  python scripts/seed-test-bank.py
"""

import json, urllib.request, urllib.error

BASE = "http://127.0.0.1:8080/api"


def api(method, path, body=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        raise SystemExit(f"[{e.code}] {method} {path}\n{body}")


# ── 1. 创建题库 ──
bank = api("POST", "/banks", {"name": "记忆曲线测试题库", "description": "测试 SM-2 间隔重复算法"})
bank_id = bank["id"]
print(f"[OK] 题库 #{bank_id}：「{bank['name']}」已创建")

# ── 2. JSON 导入题目 ──
questions = [
    # === 单选 (single) ===
    {
        "type": "single",
        "stem": "HTTP 状态码 404 表示什么？",
        "options": [
            {"key": "A", "text": "服务器内部错误"},
            {"key": "B", "text": "资源未找到"},
            {"key": "C", "text": "请求超时"},
            {"key": "D", "text": "未授权"}
        ],
        "answer": "B",
        "analysis": "404 Not Found 表示服务器无法找到请求的资源。",
        "difficulty": 2,
        "tags": ["HTTP", "网络"],
        "chapter": "计算机网络"
    },
    {
        "type": "single",
        "stem": "在关系型数据库中，哪个关键字用于从表中查询数据？",
        "options": [
            {"key": "A", "text": "INSERT"},
            {"key": "B", "text": "UPDATE"},
            {"key": "C", "text": "SELECT"},
            {"key": "D", "text": "DELETE"}
        ],
        "answer": "C",
        "analysis": "SELECT 是 SQL 中用于查询数据的基本语句。",
        "difficulty": 1,
        "tags": ["SQL", "数据库"],
        "chapter": "数据库基础"
    },
    {
        "type": "single",
        "stem": "TCP 三次握手中，客户端首先发送什么标志位的报文？",
        "options": [
            {"key": "A", "text": "ACK"},
            {"key": "B", "text": "FIN"},
            {"key": "C", "text": "SYN"},
            {"key": "D", "text": "RST"}
        ],
        "answer": "C",
        "analysis": "客户端发送 SYN=1 的报文发起连接请求，这是三次握手的第一步。",
        "difficulty": 4,
        "tags": ["TCP", "网络"],
        "chapter": "计算机网络"
    },
    {
        "type": "single",
        "stem": "Git 中用于将暂存区内容提交到本地仓库的命令是？",
        "options": [
            {"key": "A", "text": "git push"},
            {"key": "B", "text": "git add"},
            {"key": "C", "text": "git commit"},
            {"key": "D", "text": "git merge"}
        ],
        "answer": "C",
        "analysis": "git commit 将暂存区（staging area）的内容保存到本地仓库。",
        "difficulty": 1,
        "tags": ["Git", "版本控制"],
        "chapter": "开发工具"
    },
    {
        "type": "single",
        "stem": "哪种数据结构遵循先进后出（LIFO）原则？",
        "options": [
            {"key": "A", "text": "队列"},
            {"key": "B", "text": "栈"},
            {"key": "C", "text": "链表"},
            {"key": "D", "text": "哈希表"}
        ],
        "answer": "B",
        "analysis": "栈（Stack）是一种后进先出（LIFO）的数据结构，只允许在栈顶操作。",
        "difficulty": 2,
        "tags": ["数据结构"],
        "chapter": "数据结构与算法"
    },

    # === 多选 (multiple) ===
    {
        "type": "multiple",
        "stem": "以下哪些是 Python 的不可变（immutable）数据类型？",
        "options": [
            {"key": "A", "text": "列表 (list)"},
            {"key": "B", "text": "元组 (tuple)"},
            {"key": "C", "text": "字符串 (str)"},
            {"key": "D", "text": "字典 (dict)"}
        ],
        "answer": "B,C",
        "analysis": "tuple 和 str 创建后不可修改，是 immutable 类型；list 和 dict 是 mutable 的。",
        "difficulty": 3,
        "tags": ["Python", "编程基础"],
        "chapter": "Python 基础"
    },
    {
        "type": "multiple",
        "stem": "以下哪些属于 Linux 发行版？",
        "options": [
            {"key": "A", "text": "Ubuntu"},
            {"key": "B", "text": "Windows"},
            {"key": "C", "text": "CentOS"},
            {"key": "D", "text": "Debian"}
        ],
        "answer": "A,C,D",
        "analysis": "Ubuntu、CentOS 和 Debian 都是基于 Linux 内核的操作系统发行版。",
        "difficulty": 2,
        "tags": ["Linux", "操作系统"],
        "chapter": "Linux 基础"
    },
    {
        "type": "multiple",
        "stem": "React Hook 的使用规则包括哪些？",
        "options": [
            {"key": "A", "text": "只能在函数组件顶层调用"},
            {"key": "B", "text": "可以在 class 组件中使用"},
            {"key": "C", "text": "不能在循环或条件语句中调用"},
            {"key": "D", "text": "Hook 名必须以 use 开头"}
        ],
        "answer": "A,C,D",
        "analysis": "Hook 只能在函数组件或自定义 Hook 顶层调用，不能在条件/循环中使用；class 组件不支持 Hook。",
        "difficulty": 4,
        "tags": ["React", "前端"],
        "chapter": "React 进阶"
    },

    # === 填空 (fill) ===
    {
        "type": "fill",
        "stem": "CSS 中，Flexbox 布局的主轴方向由 _______ 属性控制。",
        "answer": "flex-direction",
        "analysis": "flex-direction 决定主轴方向，取值 row | row-reverse | column | column-reverse。",
        "difficulty": 3,
        "tags": ["CSS", "前端"],
        "chapter": "CSS 布局"
    },
    {
        "type": "fill",
        "stem": "在 JavaScript 中，将 JSON 字符串转为对象使用 _______ 方法。",
        "answer": "JSON.parse",
        "analysis": "JSON.parse() 将 JSON 字符串解析为 JavaScript 对象；JSON.stringify() 则相反。",
        "difficulty": 2,
        "tags": ["JavaScript", "编程基础"],
        "chapter": "JavaScript 基础"
    },
    {
        "type": "fill",
        "stem": "Docker 中，用于构建镜像的配置文件叫 _______。",
        "answer": "Dockerfile",
        "analysis": "Dockerfile 是包含构建 Docker 镜像指令的文本文件。",
        "difficulty": 2,
        "tags": ["Docker", "DevOps"],
        "chapter": "容器技术"
    },

    # === 判断 (true_false) ===
    {
        "type": "true_false",
        "stem": "HTTPS 在 HTTP 基础上通过 TLS/SSL 协议提供加密传输。",
        "answer": "true",
        "analysis": "HTTPS = HTTP + TLS/SSL，通过证书认证和加密保证通信安全。",
        "difficulty": 1,
        "tags": ["HTTP", "安全"],
        "chapter": "计算机网络"
    },
    {
        "type": "true_false",
        "stem": "在面向对象编程中，子类不能重写父类的方法。",
        "answer": "false",
        "analysis": "子类可以重写（override）父类的方法，这是多态的核心机制之一。",
        "difficulty": 2,
        "tags": ["OOP", "编程基础"],
        "chapter": "面向对象编程"
    },
    {
        "type": "true_false",
        "stem": "IPv4 地址由 32 位二进制数组成。",
        "answer": "true",
        "analysis": "IPv4 地址共 32 位，通常表示为 4 个十进制数（0-255），用点分隔。",
        "difficulty": 1,
        "tags": ["网络", "TCP/IP"],
        "chapter": "计算机网络"
    },

    # === 解答 (essay) ===
    {
        "type": "essay",
        "stem": "请简述数据库索引的原理及其优缺点。",
        "analysis": "索引通过 B+树等数据结构加速查询，以空间换时间；优点：加速查询、排序；缺点：占用存储、降低写性能。",
        "difficulty": 4,
        "tags": ["数据库", "性能优化"],
        "chapter": "数据库进阶"
    },
]
result = api("POST", f"/banks/{bank_id}/import/json", {"content": json.dumps({"questions": questions})})
print(f"[OK] 导入 {result.get('imported', len(questions))} 道题目")

# ── 3. 验证 ──
bank_detail = api("GET", f"/banks/{bank_id}")
print(f"[OK] 题库当前共 {bank_detail.get('questionCount', '?')} 道题目")
print(f"\n✅ 测试题库「记忆曲线测试题库」创建完成！")
print(f"   题目数: {len(questions)} 道（单选 5 + 多选 3 + 填空 3 + 判断 3 + 解答 1）")
print(f"   覆盖章节: 计算机网络 / 数据库 / 开发工具 / Python / React / CSS / Docker 等")
print(f"   难度分布: 简单(1)~困难(4)")
print(f"\n   下一步操作验证记忆曲线功能：")
print(f"   1. 进入「记忆曲线」页面 →「加入计划」→ 选择题目 → 加入复习计划")
print(f"   2. 切换到「今日复习」→ 逐一完成复习评级（0-5 分）")
print(f"   3. 切换到「复习统计」→ 查看统计数据")
print(f"   4. 去「设置 → 配置方案」→ 可自定义复习策略")
