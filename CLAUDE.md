# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Backend (Spring Boot / Maven)
mvn spring-boot:run                    # Start the backend (dev profile default)
mvn spring-boot:run -DskipTests        # Start without tests
mvn test                               # Run all tests
mvn test -Dtest=ChatControllerTest     # Run a single test class
mvn compile                            # Compile only
mvn clean package -DskipTests          # Build JAR

# Frontend (Vue 3 / Vite)
cd frontend && npm run dev             # Start dev server on port 5173 (proxies to :8080)
cd frontend && npm run build           # Production build to dist/

# Docker
docker-compose up -d                   # Start MySQL + Redis + app (requires env vars)
```

## Architecture

This is an AI Agent chat platform: Spring Boot 3.2.5 backend with Spring AI 1.0.0-M1 and a Vue 3 frontend.

### Layer structure (`com.niit.agent`)

| Layer | Package | Purpose |
|-------|---------|---------|
| Controllers | `controller/` | REST endpoints and SSE streaming |
| Services | `service/` | Business logic interfaces |
| Service Impls | `service/impl/` | Concrete implementations (MyBatis-Plus `ServiceImpl` subclasses) |
| Mappers | `mapper/` | MyBatis-Plus BaseMapper interfaces |
| Entities | `entity/` | JPA entities mapped to MySQL tables |
| VOs | `vo/` | Request/response DTOs |
| Config | `config/` | Spring configuration, interceptors, filters |
| Common | `common/` | JWT utils, exceptions, Result wrapper, text utilities |

### AI model abstraction

`service/model/AiModel` is the core interface for LLM providers. `AbstractOpenAiCompatibleModel` provides a shared base using OkHttp for raw API calls with SSE parsing. Each model implementation handles its own API format differences.

`AiModelRouterService` wraps model access with a **dual-lane queue** (`PRIMARY` for user chat, `AUXILIARY` for background tasks like chunk summarization), circuit breaker (Resilience4j), and distributed Redis coordination. Models are configured via `ModelConfig` entities in the database, cached with Caffeine.

### RAG pipeline (in `ChatAttachmentServiceImpl` and `MemoryServiceImpl`)

1. **Ingestion**: Apache Tika parses uploads (PDF, Word, TXT, Excel) → `TokenTextSplitter` chunks by 800 tokens → stored in Redis vector store
2. **Retrieval**: Hybrid search combining (a) Redis vector similarity and (b) RediSearch BM25 keyword matching, with optional query expansion
3. **Rerank**: Zhipu BGE-Reranker (`ZhipuRerankServiceImpl`) cross-encodes retrieved candidates, returning top-K
4. **Context injection**: RAG results merged into system prompt under `[知识库检索结果开始]` markers
5. Every stage has independent fallback — if any component fails, the pipeline degrades gracefully

### Function Calling (Agent tools)

`service/tools/` implements the agent tool system:
- `ToolHandler` interface: `getName()`, `getDescription()`, `getParameters()`, `execute()`
- `ToolRegistry`: auto-discovers all `ToolHandler` beans via constructor injection, builds OpenAI-compatible tool definitions
- Tools: `TimeTool`, `WeatherTool`, `CalculatorTool`, `KnowledgeSearchTool`, `WebSearchTool` (DuckDuckGo scraping, disabled by default)
- `ChatController.doStreamResponse()` handles recursive tool calls: detects `tool_calls` in SSE stream, executes via `ToolRegistry`, wraps result as `tool` role message, feeds back to model (max 5 recursive rounds)

### Context / memory management (`MemoryServiceImpl`)

- Builds conversation context from `ChatMessage` rows, applying a sliding window based on token budget (`ai.context.max-length: 4000`)
- When token threshold exceeded (`ai.summary.trigger-tokens: 3000`), asynchronously generates a summary using a cheaper model (`glm-4.5-air`) and injects it into the system prompt
- `buildContext()` also triggers RAG retrieval (using the latest user message as query) and merges tool definitions when tools are enabled

### Rate limiting (`RateLimitFilter`)

- **Distributed**: Redis Lua script (INCR + EXPIRE) for accurate cross-instance counting
- **Local fallback**: bucket4j token buckets when Redis is unavailable
- Per-endpoint policies: stream (20/min), upload (10/min), auth (10/min), default (60/min)

### Auth

JWT-based via `JwtInterceptor`. All paths intercepted except `/user/login`, `/user/register`, `/model/list`. Tokens carry `userId` and `role` (admin/user). Admin endpoints in `AdminController` check `request.getAttribute("role")`.

### SSE protocol

The `/chat/stream` endpoint uses `SseEmitter` with 180s timeout:
- Chunks prefixed `R` → sent as `reasoning` named events (thinking/reasoning content)
- Chunks prefixed `C` → sent as default events (visible output)  
- Heartbeat comments every 15s to prevent proxy timeout
- `[DONE]` signals completion
- `queue_status` named events convey queue position

### Frontend (`frontend/`)

Vue 3 + TypeScript + Element Plus + Vite 8. Key files:
- `src/App.vue` — main chat UI component
- `src/api.ts` — axios instance and REST API calls
- `src/chatApi.ts` — SSE streaming logic and chat-specific calls
- `src/types/index.ts` — TypeScript interfaces mirroring backend VOs
- `src/utils/markdown.ts` — marked + highlight.js helpers
- `src/components/` — `ChatSidebar.vue`, `ChatMain.vue`, `ThinkingSection.vue`, etc.

Vite proxies `/user`, `/session`, `/chat`, `/message`, `/model`, `/upload` to `localhost:8080` in dev.

### Database

MySQL via MyBatis-Plus. Key tables: `user`, `chat_session`, `chat_message`, `chat_attachment`, `chat_summary`, `model_config`, `session_tag`, `session_tag_relation`, `prompt_template`.
Redis Stack is required (RediSearch + RedisJSON modules) for the vector store.
