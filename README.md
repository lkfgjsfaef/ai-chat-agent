# 🚀 智能 AI 助手后端系统 (Agent AI Backend)

这是一个基于 **Spring Boot 3 + Spring AI** 构建的企业级 AI 智能对话与 Agent 后端系统。项目不仅实现了主流的大模型流式对话（SSE），还深入集成了 **RAG（检索增强生成）**、**Function Calling（工具调用）** 以及**精细化 Token 监控**等前沿架构。

---

## 🌟 核心特性与实现细节 (Features & Implementation)

### 1. 🤖 真正的 Agent 智能体架构 (Function Calling)
- 告别纯对话系统！模型现在可以**自主决定**调用外部 Java 工具来获取实时数据。
- **内置工具**：
  - `get_weather`：调用天气服务（模拟）。
  - `get_current_time`：获取服务器系统时间。
- **底层实现**：
  - 采用接口抽象 `ToolHandler`，通过 `ToolRegistry` 自动扫描注册。
  - 支持**递归流式工具调用**：在 SSE 响应流中截获 `tool_calls`，自动暂停输出，执行本地 Java 方法后，将结果包装为 `tool` 角色无缝发回给大模型继续流式生成。
  - **防死循环机制**：严格限制最大工具递归调用次数为 5 次，并在 JSON 解析异常时优雅降级。

### 2. 📚 深度调优的企业级 RAG (检索增强生成)
- 解决了纯向量检索不准、大模型“幻觉”等业务痛点。
- **多格式文档解析**：集成 `Apache Tika`，支持 PDF、Word、TXT、Excel 等格式的高效文本提取。
- **科学分块 (Chunking)**：使用 Spring AI 的 `TokenTextSplitter` 策略，按照 Token 大小 (ChunkSize=800) 切分长文档，避免硬截断导致语义丢失。
- **多路召回 (Hybrid Search)**：
  - 语义检索：基于 Spring AI `RedisVectorStore`。
  - 字面检索：基于 `UnifiedJedis` 调用 RediSearch 的 `FT.SEARCH` 实现 BM25 关键字检索。
  - 系统会将两路召回的结果进行合并去重。
- **结果重排 (Rerank)**：对接智谱官方 `rerank-1` (BGE-Reranker) 模型，对召回的混合文档进行交叉打分，精准提取 Top 3 相关片段注入上下文。
- **高可用容错**：向量检索、BM25检索与 Rerank 均拥有独立的异常捕获与降级逻辑（Fallback），即使 Rerank 接口宕机，也能截取前 N 个片段保证问答继续。

### 3. 🧠 智能 Token 精算与上下文管理
- **Token 级精确计算**：引入 `jtokkit` (基于 CL100K_BASE 编码，适配 GPT-4/GLM-4)，精确计算 Prompt、Completion 以及 Tool 交互的 Token 消耗。
- **滑动窗口截断机制**：不再简单粗暴地按“轮数”截断对话，而是基于剩余可用 Token 进行精细的滑动窗口截断，最大化保留 System Prompt 和关键的工具交互历史。
- **自动摘要生成**：当上下文长度逼近模型极限时，系统会在后台自动触发异步任务，提取长对话的精简摘要并注入到后续对话中。

### 4. 📈 运营级管理大盘 (Admin Dashboard)
- **RBAC 角色权限体系**：集成 JWT 鉴权，支持 `admin` 与 `user` 角色区分，通过拦截器实现接口级权限管控。
- **全局统计 API**：
  - `/admin/stats/dashboard`：可视化追踪全站的注册用户数、活跃模型数。
  - `/admin/users`：分页查看全站用户列表，并按**累计 Token 消耗量**进行排行监控。

### 5. 💬 核心对话与会话管理
- **多模型无缝切换**：支持 DeepSeek、智谱 GLM 系列 (4.5-air/4.6v/4.7)、OpenAI 等，基于策略模式动态路由。
- **对话交互增强**：
  - 支持会话级**自定义系统提示词 (System Prompt)**。
  - 支持单条消息的重新生成 (Regenerate)。
  - 支持用户对 AI 的回答进行点赞/踩 (Feedback) 反馈。
  - 自动根据首轮对话生成会话标题。
- **多维分类系统**：
  - 支持自定义**对话标签 (Session Tags)**，一个会话可打多个颜色标签进行分组管理。
  - 提供快捷的**提示词模板库 (Prompt Templates)**。
  - 支持历史消息的全文关键字检索。

### 6. ⚡ 高并发与极致网络优化
- 采用 **Reactor (Flux)** 响应式编程模型，原生支持 SSE (Server-Sent Events) 打字机效果。
- **OkHttp 连接池预热**：通过 `@PostConstruct` 在系统启动时并发预热 TLS 连接，解决冷启动导致的并发排队，大幅降低 TTFT（首字响应时间）。
- **统一异常处理**：全局拦截并格式化 REST 和 SSE 流式请求中的异常，确保前端在网络抖动或大模型报错时能瞬间解锁界面。

---

## 🏗️ 架构概览 (Architecture)

```text
+-------------------+      +------------------+      +-------------------+
|  Web Client (Vue) | <--> |  Spring Boot API | <--> | LLM API (GLM/GPT) |
+-------------------+      +------------------+      +-------------------+
                                   |   |
                    +--------------+   +--------------+
                    |                                 |
           +------------------+              +-----------------+
           |   RAG Engine     |              |  Tool Registry  |
           | (Redis Vector)   |              | (Function Call) |
           +------------------+              +-----------------+
```

- **核心技术栈**：
  - 后端框架：Spring Boot 3.2.5
  - AI 框架：Spring AI (1.0.0-M1)
  - 数据库与 ORM：MySQL 8.0+ / MyBatis-Plus 3.5.5
  - 缓存与向量库：Redis Stack (RediSearch / RedisJSON) / Jedis 5.1.0
  - HTTP 客户端：OkHttp3 + OkHttp-SSE
  - Token 计算：JTokkit
  - 文档解析：Apache Tika

---

## 🛠️ 快速开始 & 测试指南 (Testing Guide)

为了确保您能完整体验系统的各项高级特性，请按照以下步骤进行测试：

### 1. 环境准备
1. 确保已启动 **MySQL 8.0+** 并执行了 `sql/init.sql`（系统启动时会自动检查并补全 `user` 表的 `role` 字段）。
2. 确保已启动 **Redis Stack**（必须包含 RediSearch 和 RedisJSON 模块）。推荐使用 Docker 启动：
   ```bash
   docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest
   ```
3. 在 `application.yml` 中配置好智谱 AI 的 `api-key`。

### 2. 测试 Agent 工具调用 (Function Calling)
1. 启动项目，调用 `POST /user/login` 登录获取 Token。
2. 开启一个新对话：
   - **提问**：`"现在几点了？"`
   - **预期表现**：大模型由于自身没有时间概念，会自动触发 `get_current_time` 工具，系统会在后台打印 `执行工具 [get_current_time]`，随后模型流式输出准确的系统时间。
   - **提问**：`"北京今天天气怎么样？"`
   - **预期表现**：模型触发 `get_weather` 工具，返回“北京今天天气晴朗...”。

### 3. 测试 RAG 多路召回与重排 (Hybrid Search & Rerank)
1. **准备数据**：调用 `POST /chat/attachment/upload` 上传一份文本或 PDF 文件。系统会自动使用 Tika 解析并进行 Token 分块，存入 Redis 向量库。
2. **提问测试**：根据上传的文档内容提出一个问题。
3. **预期后台日志**：
   - 打印 `执行RAG混合检索...`。
   - 触发 Vector Search 和 BM25 字面检索，并将两路结果合并。
   - 打印 `Rerank 完成, 从 X 篇文档中重排提取了 3 篇`。
4. **预期前端表现**：大模型会结合检索到的精准片段（系统提示词中会包含 `[知识库检索结果开始]`）为您生成回答。

### 4. 测试 Token 精算与管理后台
1. 在 MySQL 数据库中，手动将您登录账号的 `role` 字段修改为 `admin`。
2. 进行几次对话，确保 `user` 表中的 `total_tokens` 字段由于 `jtokkit` 的精准计算正在累加。
3. 调用 `GET /admin/stats/dashboard` 接口。
   - **预期表现**：返回全站的总用户数、总 Token 消耗量以及活跃的模型数量。

---

## 📌 未来规划 (Roadmap)
- 接入更多实用 Agent 工具（如执行本地 Python 代码的沙箱、数据库自然语言查询工具）。
- 提供前端可视化的大盘监控页面，利用 ECharts 展示 Token 消耗曲线。
- 支持基于 WebSocket 的双向长连接，优化多设备消息同步。
