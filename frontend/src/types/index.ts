export interface LoginForm {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  username: string
}

export interface UserProfile {
  nickname?: string
  avatarUrl?: string
  bio?: string
  theme?: 'dark' | 'light'
  totalTokens?: number
  hasApiKey?: boolean
}

export interface ChatSession {
  id: number
  title: string
  systemPrompt?: string
  skillId?: string
  tags?: SessionTag[]
  userId?: number
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'user' | 'assistant'
  content: string
  feedback?: 'like' | 'dislike' | null
  createTime?: string
}

export interface ModelInfo {
  modelName: string
  supportsVision: boolean
}

export interface SessionTag {
  id: number
  name: string
  color: string
}

export interface AppSkill {
  id: string
  name: string
  placeholder?: string
}

export interface PromptTemplate {
  id: number
  title: string
  content: string
}

export interface QueueStatus {
  type: 'queue_status'
  phase: 'queued' | 'acquired' | 'streaming'
  message: string
  waitingRequests?: number
  waitedMs?: number
}

export interface KnowledgeAttachment {
  id: number
  sessionId?: number
  userId?: number
  scope: string
  fileName: string
  fileSize: number
  fileType?: string
  extractedText?: string
  createTime?: string
  chunkCount?: number
  canDelete?: boolean
}

export interface KnowledgeAttachmentPage {
  current: number
  size: number
  total: number
  pages: number
  records: KnowledgeAttachment[]
  scopeStats: Record<string, number>
}

export interface ApiResponse<T = unknown> {
  code: number
  msg: string
  data: T
}

export interface SearchResult {
  id: string
  sessionId: number
  sessionTitle: string
  matchContent: string | null
}

export interface MessageVO {
  id: number
  sessionId: number
  role: 'user' | 'assistant'
  content: string
  feedback?: 'like' | 'dislike' | null
  createTime?: string
}

export interface SessionVO {
  id: number
  title: string
  systemPrompt?: string
  skillId?: string
  tags?: SessionTag[]
  createTime?: string
}
