import request from './api'
import type {
  LoginForm, ApiResponse, LoginResponse, UserProfile,
  SessionTag, ChatSession, ChatMessage, MessageVO,
  ModelInfo, PromptTemplate, AppSkill, KnowledgeAttachment,
  KnowledgeAttachmentPage
} from './types'

export function login(data: LoginForm): Promise<ApiResponse<LoginResponse>> {
  return request.post('/user/login', data) as Promise<ApiResponse<LoginResponse>>
}

export function register(data: LoginForm): Promise<ApiResponse<unknown>> {
  return request.post('/user/register', data) as Promise<ApiResponse<unknown>>
}

export function getUserProfile(): Promise<ApiResponse<UserProfile>> {
  return request.get('/user/profile') as Promise<ApiResponse<UserProfile>>
}

export function updateUserProfile(data: Partial<UserProfile>): Promise<ApiResponse<unknown>> {
  return request.put('/user/profile', data) as Promise<ApiResponse<unknown>>
}

export function uploadAvatar(formData: FormData): Promise<ApiResponse<string>> {
  return request.post('/user/avatar', formData) as Promise<ApiResponse<string>>
}

export function listTags(): Promise<ApiResponse<SessionTag[]>> {
  return request.get('/tag/list') as Promise<ApiResponse<SessionTag[]>>
}

export function createTag(data: { name: string; color: string }): Promise<ApiResponse<SessionTag>> {
  return request.post('/tag/create', data) as Promise<ApiResponse<SessionTag>>
}

export function updateTag(tagId: number, data: Partial<SessionTag>): Promise<ApiResponse<unknown>> {
  return request.put('/tag/' + tagId, data) as Promise<ApiResponse<unknown>>
}

export function deleteTag(tagId: number): Promise<ApiResponse<unknown>> {
  return request.delete('/tag/' + tagId) as Promise<ApiResponse<unknown>>
}

export function addTagToSession(sessionId: number, tagId: number): Promise<ApiResponse<unknown>> {
  return request.post('/tag/session/' + sessionId + '/' + tagId) as Promise<ApiResponse<unknown>>
}

export function removeTagFromSession(sessionId: number, tagId: number): Promise<ApiResponse<unknown>> {
  return request.delete('/tag/session/' + sessionId + '/' + tagId) as Promise<ApiResponse<unknown>>
}

export function createSession(data: { title?: string }): Promise<ApiResponse<ChatSession>> {
  return request.post('/session/create', data) as Promise<ApiResponse<ChatSession>>
}

export function listSkills(): Promise<ApiResponse<AppSkill[]>> {
  return request.get('/skill/list') as Promise<ApiResponse<AppSkill[]>>
}

export function listSessions(): Promise<ApiResponse<ChatSession[]>> {
  return request.get('/session/list') as Promise<ApiResponse<ChatSession[]>>
}

export function deleteSession(sessionId: number): Promise<ApiResponse<unknown>> {
  return request.delete('/session/' + sessionId) as Promise<ApiResponse<unknown>>
}

export function searchSessions(keyword: string): Promise<ApiResponse<ChatSession[]>> {
  return request.get('/session/search', { params: { keyword } }) as Promise<ApiResponse<ChatSession[]>>
}

export function searchMessages(keyword: string): Promise<ApiResponse<MessageVO[]>> {
  return request.get('/message/search', { params: { keyword } }) as Promise<ApiResponse<MessageVO[]>>
}

export function renameSession(sessionId: number, title: string): Promise<ApiResponse<unknown>> {
  return request.put('/session/' + sessionId + '/title', null, { params: { title } }) as Promise<ApiResponse<unknown>>
}

export function updateSystemPrompt(sessionId: number, systemPrompt: string): Promise<ApiResponse<unknown>> {
  return request.put('/session/' + sessionId + '/prompt', { systemPrompt }) as Promise<ApiResponse<unknown>>
}

export function updateSessionSkill(sessionId: number, skillId: string): Promise<ApiResponse<unknown>> {
  return request.put('/session/' + sessionId + '/skill', { skillId }) as Promise<ApiResponse<unknown>>
}

export function listMessages(sessionId: number): Promise<ApiResponse<MessageVO[]>> {
  return request.get('/message/list', { params: { sessionId } }) as Promise<ApiResponse<MessageVO[]>>
}

export function updateMessage(messageId: number, sessionId: number, content: string): Promise<ApiResponse<unknown>> {
  return request.put('/message/' + messageId, { content }, { params: { sessionId } }) as Promise<ApiResponse<unknown>>
}

export function deleteAfterMessage(sessionId: number, afterMessageId: number): Promise<ApiResponse<unknown>> {
  return request.delete('/message/after', { params: { sessionId, afterMessageId } }) as Promise<ApiResponse<unknown>>
}

export function updateFeedback(messageId: number, sessionId: number, feedback: string): Promise<ApiResponse<unknown>> {
  return request.put('/message/' + messageId + '/feedback', { feedback }, { params: { sessionId } }) as Promise<ApiResponse<unknown>>
}

export function listModels(): Promise<ApiResponse<ModelInfo[]>> {
  return request.get('/model/list') as Promise<ApiResponse<ModelInfo[]>>
}

export function listTemplates(): Promise<ApiResponse<PromptTemplate[]>> {
  return request.get('/template/list') as Promise<ApiResponse<PromptTemplate[]>>
}

export function uploadFile(file: File, sessionId?: number): Promise<ApiResponse<KnowledgeAttachment>> {
  const formData = new FormData()
  formData.append('file', file)
  if (sessionId) formData.append('sessionId', String(sessionId))
  return request.post('/upload/file', formData, { timeout: 300000 }) as Promise<ApiResponse<KnowledgeAttachment>>
}

export function uploadImage(file: File, sessionId?: number, scope?: string): Promise<ApiResponse<KnowledgeAttachment>> {
  const formData = new FormData()
  formData.append('file', file)
  if (sessionId) formData.append('sessionId', String(sessionId))
  if (scope) formData.append('scope', scope)
  return request.post('/upload/image', formData) as Promise<ApiResponse<KnowledgeAttachment>>
}

export function uploadToKnowledgeBase(file: File, scope?: string, sessionId?: number): Promise<ApiResponse<KnowledgeAttachment>> {
  const formData = new FormData()
  formData.append('file', file)
  if (scope) formData.append('scope', scope)
  if (sessionId) formData.append('sessionId', String(sessionId))
  return request.post('/upload/file', formData, { timeout: 300000 }) as Promise<ApiResponse<KnowledgeAttachment>>
}

export function listKnowledgeFiles(params: {
  scope?: string
  keyword?: string
  current?: number
  size?: number
}): Promise<ApiResponse<KnowledgeAttachmentPage>> {
  return request.get('/upload/files', { params }) as Promise<ApiResponse<KnowledgeAttachmentPage>>
}

export function deleteKnowledgeFile(attachmentId: number): Promise<ApiResponse<null>> {
  return request.delete('/upload/file/' + attachmentId) as Promise<ApiResponse<null>>
}

export function regenerate(data: { sessionId: number; content: string }): Promise<ApiResponse<unknown>> {
  return request.post('/chat/regenerate', data) as Promise<ApiResponse<unknown>>
}

export function streamChat(sessionId: number, content: string, modelName?: string, skillId?: string): Promise<ApiResponse<unknown>> {
  const params = new URLSearchParams({ sessionId: String(sessionId), content })
  if (modelName) params.append('modelName', modelName)
  if (skillId) params.append('skillId', skillId)
  return request.get('/chat/stream', { params }) as Promise<ApiResponse<unknown>>
}

export function saveApiKey(apiKey: string): Promise<ApiResponse<unknown>> {
  return request.put('/user/apikey', { apiKey }) as Promise<ApiResponse<unknown>>
}

export function deleteApiKey(): Promise<ApiResponse<unknown>> {
  return request.delete('/user/apikey') as Promise<ApiResponse<unknown>>
}
