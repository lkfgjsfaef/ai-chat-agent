<template>
  <div v-if="!isLoggedIn" class="auth-page">
    <canvas ref="particleCanvas" class="particle-canvas"></canvas>

    <div class="auth-container" :class="{ 'register-mode': isRegister }">
      <div class="auth-left">
        <div class="brand-section">
          <div class="brand-icon">
            <svg viewBox="0 0 80 80" class="ai-svg">
              <defs>
                <linearGradient id="grad1" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" style="stop-color:#818cf8;stop-opacity:1" />
                  <stop offset="100%" style="stop-color:#c084fc;stop-opacity:1" />
                </linearGradient>
              </defs>
              <circle cx="40" cy="40" r="35" fill="none" stroke="url(#grad1)" stroke-width="1.5" class="pulse-ring" />
              <circle cx="40" cy="40" r="20" fill="none" stroke="url(#grad1)" stroke-width="1" class="pulse-ring" />
              <circle cx="40" cy="40" r="6" fill="url(#grad1)" class="core-dot" />
            </svg>
          </div>
          <h1 class="brand-title">AI Chat</h1>
          <p class="brand-desc">企业级智能体工作台 · 更可信的 RAG 对话体验</p>
          <div class="feature-list">
            <div v-for="item in authFeatureItems" :key="item" class="feature-item">
              <span class="feature-dot"></span>
              <span>{{ item }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="auth-right">
        <div class="auth-form-wrapper">
          <transition name="slide-fade" mode="out-in">
            <div v-if="!isRegister" key="login" class="form-content">
              <h2 class="form-title">欢迎回来</h2>
              <p class="form-subtitle">登录你的账号继续对话</p>
              <el-form :model="loginForm" @submit.prevent="handleLogin" class="auth-form">
                <div class="input-group">
                  <el-input v-model="loginForm.username" placeholder="请输入用户名" prefix-icon="User" size="large" clearable />
                </div>
                <div class="input-group">
                  <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock" size="large" show-password />
                </div>
                <el-button class="submit-btn" type="primary" @click="handleLogin" :loading="loginLoading">
                  <span>登 录</span>
                </el-button>
              </el-form>
              <div class="switch-link">
                还没有账号？<a @click="isRegister = true">立即注册</a>
              </div>
            </div>

            <div v-else key="register" class="form-content">
              <h2 class="form-title">创建账号</h2>
              <p class="form-subtitle">注册一个新的AI对话账号</p>
              <el-form :model="registerForm" @submit.prevent="handleRegister" class="auth-form">
                <div class="input-group">
                  <el-input v-model="registerForm.username" placeholder="设置用户名" prefix-icon="User" size="large" clearable />
                </div>
                <div class="input-group">
                  <el-input v-model="registerForm.password" type="password" placeholder="设置密码" prefix-icon="Lock" size="large" show-password />
                </div>
                <div class="input-group">
                  <el-input v-model="registerForm.confirmPassword" type="password" placeholder="确认密码" prefix-icon="Lock" size="large" show-password />
                </div>
                <el-button class="submit-btn" type="primary" @click="handleRegister" :loading="loginLoading">
                  <span>注 册</span>
                </el-button>
              </el-form>
              <div class="switch-link">
                已有账号？<a @click="isRegister = false">返回登录</a>
              </div>
            </div>
          </transition>
        </div>
      </div>
    </div>
  </div>

  <div v-else class="chat-container">
    <el-dialog v-model="promptDialogVisible" title="设置系统提示词" width="500px">
      <el-input
        v-model="tempSystemPrompt"
        type="textarea"
        :rows="6"
        placeholder="例如：你是一个专业的Java开发工程师，请用简洁的代码和中文注释回答问题。"
      />
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="promptDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveSystemPrompt" :loading="savingPrompt">保存</el-button>
        </span>
      </template>
    </el-dialog>

    <div class="mobile-overlay" v-if="mobileSidebarVisible" @click="mobileSidebarVisible = false"></div>
    <div class="sidebar" :class="{ 'mobile-show': mobileSidebarVisible }">
      <div class="sidebar-header">
        <div class="sidebar-brand">
          <div class="brand-mini-icon">
            <svg viewBox="0 0 40 40" width="28" height="28">
              <circle cx="20" cy="20" r="16" fill="none" stroke="#818cf8" stroke-width="1.5" />
              <circle cx="20" cy="20" r="4" fill="#818cf8" />
            </svg>
          </div>
          <div class="sidebar-brand-copy">
            <div class="sidebar-brand-title">
              <h2>AI Chat</h2>
              <span class="sidebar-badge">RAG</span>
            </div>
            <p class="sidebar-subtitle">检索增强 · 多模型调度 · 流式协作</p>
          </div>
        </div>
        <el-button class="new-chat-btn" type="primary" @click="createNewSession" :icon="Plus">新建对话</el-button>
        <el-button class="knowledge-btn" :class="{ active: currentView === 'knowledge' }" @click="switchToKnowledgeView" :icon="Upload">知识库</el-button>
      </div>
      <div class="sidebar-search">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索历史对话..."
          prefix-icon="Search"
          clearable
          @input="debouncedSearch"
        />
      </div>
      <div class="sidebar-tags" v-if="allTags.length > 0 && !searchKeyword">
        <el-select v-model="filterTagId" placeholder="按标签筛选" clearable size="small" class="tag-filter-select">
          <el-option v-for="t in allTags" :key="t.id" :label="t.name" :value="t.id">
            <span class="tag-color-dot" :style="{ backgroundColor: t.color }"></span>
            {{ t.name }}
          </el-option>
        </el-select>
        <el-button size="small" :icon="Edit" @click="manageTagsDialogVisible = true" circle title="管理标签" />
      </div>
      <div v-if="searchKeyword" class="search-results">
        <div v-if="isSearching" class="search-loading">搜索中...</div>
        <div v-else-if="searchResults.length === 0" class="empty-sessions">未找到相关内容</div>
        <div v-else class="search-list">
          <div v-for="item in searchResults" :key="item.id" class="search-item" @click="jumpToSession(item.sessionId)">
            <div class="search-item-title">{{ item.sessionTitle }}</div>
            <div v-if="item.matchContent" class="search-item-content" v-html="highlightKeyword(item.matchContent)"></div>
          </div>
        </div>
      </div>
      <div v-else class="session-list">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
          :class="['session-item', { active: currentSessionId === session.id }]"
          @click="switchSession(session.id)"
        >
          <div class="session-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
          </div>
          <template v-if="editingSessionId === session.id">
            <input
              ref="editInputRef"
              v-model="editingTitle"
              class="session-edit-input"
              @click.stop
              @keyup.enter="finishEditSession(session.id)"
              @keyup.escape="cancelEditSession"
              @blur="finishEditSession(session.id)"
            />
          </template>
          <template v-else>
            <div class="session-title-wrapper" @dblclick="startEditSession(session)">
              <span class="session-title">{{ session.title }}</span>
              <div class="session-tags" v-if="session.tags && session.tags.length > 0">
                <span v-for="t in session.tags" :key="t.id" class="session-tag-dot" :style="{ backgroundColor: t.color }" :title="t.name"></span>
              </div>
            </div>
          </template>
          <span class="session-actions">
            <span class="session-tag-btn" @click.stop="openSessionTags(session)" title="打标签">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"></path>
                <line x1="7" y1="7" x2="7.01" y2="7"></line>
              </svg>
            </span>
            <span class="session-edit" @click.stop="startEditSession(session)" title="重命名">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
            </span>
            <span class="session-delete" @click.stop="handleDeleteSession(session.id)" title="删除">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </span>
          </span>
        </div>
        <div v-if="sessions.length === 0" class="empty-sessions">
          <p>暂无对话</p>
          <p class="empty-hint">点击上方按钮开始</p>
        </div>
      </div>
      <div class="sidebar-footer">
        <div class="model-selector">
          <div class="model-label">当前模型</div>
          <el-select v-model="currentModel" class="model-select" placeholder="选择模型" size="default">
            <el-option v-for="m in models" :key="m.modelName" :label="m.modelName" :value="m.modelName" />
          </el-select>
        </div>
        <div class="user-info" @click="openProfileDialog">
          <div class="user-avatar-small">
            <img v-if="userProfile.avatarUrl" :src="userProfile.avatarUrl" class="avatar-img" />
            <span v-else>{{ (userProfile.nickname || username).charAt(0).toUpperCase() }}</span>
          </div>
          <div class="user-details">
            <span class="username">{{ userProfile.nickname || username }}</span>
            <span class="token-usage">已用 Token: {{ userProfile.totalTokens || 0 }}</span>
          </div>
          <span class="logout-text">设置</span>
        </div>
      </div>
    </div>

    <el-dialog v-model="profileDialogVisible" title="个人设置" width="500px">
      <el-form :model="userProfile" label-width="80px">
        <el-form-item label="头像">
          <div class="avatar-uploader" @click="triggerAvatarUpload">
            <img v-if="userProfile.avatarUrl" :src="userProfile.avatarUrl" class="avatar" />
            <div v-else class="avatar-placeholder">
              <span>{{ (userProfile.nickname || username).charAt(0).toUpperCase() }}</span>
            </div>
            <input type="file" ref="avatarInput" style="display: none" accept="image/*" @change="handleAvatarUpload" />
          </div>
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="userProfile.nickname" placeholder="设置昵称" />
        </el-form-item>
        <el-form-item label="个性签名">
          <el-input v-model="userProfile.bio" type="textarea" placeholder="写点什么..." />
        </el-form-item>
        <el-form-item label="主题偏好">
          <el-radio-group v-model="userProfile.theme">
            <el-radio label="dark">暗色</el-radio>
            <el-radio label="light">亮色</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <div style="display: flex; justify-content: space-between;">
          <el-button type="danger" text @click="handleLogout">退出登录</el-button>
          <div>
            <el-button @click="profileDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="saveProfile" :loading="savingProfile">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="sessionTagsDialogVisible" title="管理会话标签" width="400px">
      <div v-if="allTags.length === 0" class="empty-hint">暂无标签，请先创建标签</div>
      <div v-else class="tag-checkbox-list">
        <el-checkbox-group v-model="currentSessionTagIds">
          <el-checkbox v-for="t in allTags" :key="t.id" :label="t.id" :value="t.id">
            <span class="tag-color-dot" :style="{ backgroundColor: t.color }"></span>
            {{ t.name }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="sessionTagsDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="saveSessionTags" :loading="savingSessionTags">保存</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="manageTagsDialogVisible" title="全局标签管理" width="500px">
      <div class="manage-tags-list">
        <div v-for="t in allTags" :key="t.id" class="manage-tag-item">
          <div class="manage-tag-info">
            <span class="tag-color-dot" :style="{ backgroundColor: t.color }"></span>
            <span>{{ t.name }}</span>
          </div>
          <el-button size="small" type="danger" text @click="handleDeleteTag(t.id)">删除</el-button>
        </div>
      </div>
      <div class="add-tag-form">
        <el-input v-model="newTagName" placeholder="新标签名称" size="small" style="width: 150px" />
        <el-color-picker v-model="newTagColor" size="small" />
        <el-button size="small" type="primary" @click="handleAddTag">添加</el-button>
      </div>
    </el-dialog>

    <div class="main-area">
      <div class="chat-header">
        <div class="header-left">
          <el-icon class="mobile-menu-btn" @click="mobileSidebarVisible = true"><Expand /></el-icon>
            <div class="session-heading">
              <span class="session-eyebrow">{{ currentSessionId ? 'Active Workspace' : 'Enterprise AI Workspace' }}</span>
              <h3>{{ currentSessionTitle }}</h3>
            </div>
            <span v-if="currentSessionId" class="model-badge">{{ currentModel }}</span>
        </div>
        <div v-if="currentSessionId" class="header-actions">
            <div class="header-insights">
              <div v-for="item in headerInsights" :key="item.label" class="insight-pill">
                <span>{{ item.label }}</span>
                <strong>{{ item.value }}</strong>
              </div>
            </div>
          <el-select
            v-if="skills.length > 0 && currentSessionId"
            v-model="sessionSkillId"
            size="small"
            class="skill-select session-skill-select"
            placeholder="会话模式"
            @change="handleUpdateSessionSkill"
          >
            <el-option label="自动模式（推荐）" value="" />
            <el-option v-for="skill in skills" :key="skill.id" :label="skill.name" :value="skill.id" />
          </el-select>
          <el-button size="small" @click="openPromptDialog" :icon="Edit">提示词设置</el-button>
          <el-dropdown trigger="click" @command="exportChat">
            <el-button size="small" :icon="Download">
              导出<span class="dropdown-arrow">▾</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="markdown">Markdown (.md)</el-dropdown-item>
                <el-dropdown-item command="json">JSON (.json)</el-dropdown-item>
                <el-dropdown-item command="txt">纯文本 (.txt)</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button size="small" @click="handleClearMessages" :icon="Delete" :disabled="isStreaming">清空对话</el-button>
        </div>
      </div>

      <div v-if="currentView === 'knowledge'" class="knowledge-panel">
        <div class="knowledge-header">
          <div class="knowledge-title-row">
            <h3>知识库管理</h3>
            <el-button type="primary" @click="openKnowledgeUpload" :icon="Upload">上传文件</el-button>
          </div>
          <div class="knowledge-stats" v-if="Object.keys(knowledgeScopeStats).length > 0">
            <span class="knowledge-stat-item">
              会话知识: <strong>{{ knowledgeScopeStats.session || 0 }}</strong>
            </span>
            <span class="knowledge-stat-item">
              用户知识: <strong>{{ knowledgeScopeStats.user || 0 }}</strong>
            </span>
            <span class="knowledge-stat-item">
              全局知识: <strong>{{ knowledgeScopeStats.global || 0 }}</strong>
            </span>
          </div>
          <div class="knowledge-search">
            <el-input
              v-model="knowledgeSearchKeyword"
              placeholder="搜索文件..."
              prefix-icon="Search"
              clearable
              @input="debounceLoadKnowledgeFiles"
              size="small"
              style="max-width: 300px"
            />
          </div>
        </div>

        <el-table
          :data="knowledgeFiles"
          v-loading="knowledgeLoading"
          empty-text="知识库中暂无文件，点击上方按钮上传"
          class="knowledge-table"
          stripe
        >
          <el-table-column prop="fileName" label="文件名" min-width="200">
            <template #default="{ row }">
              <span class="knowledge-file-name">📄 {{ row.fileName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="scope" label="作用域" width="90">
            <template #default="{ row }">
              <el-tag :type="scopeTagType(row.scope)" size="small">{{ scopeLabel(row.scope) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="分块数" width="80" align="center" />
          <el-table-column label="大小" width="100">
            <template #default="{ row }">
              {{ formatFileSize(row.fileSize) }}
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="上传时间" width="170">
            <template #default="{ row }">
              {{ row.createTime || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.canDelete"
                size="small"
                type="danger"
                text
                @click="handleDeleteKnowledgeFile(row.id, row.fileName)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="knowledge-pagination" v-if="knowledgePagination.total > knowledgePagination.size">
          <el-pagination
            v-model:current-page="knowledgePagination.current"
            :page-size="knowledgePagination.size"
            :total="knowledgePagination.total"
            layout="prev, pager, next"
            @current-change="loadKnowledgeFiles"
          />
        </div>
      </div>

      <el-dialog v-model="knowledgeUploadDialogVisible" title="上传文件到知识库" width="480px">
        <el-form label-width="80px">
          <el-form-item label="作用域">
            <el-radio-group v-model="knowledgeUploadScope">
              <el-radio label="user">用户知识（所有对话可见）</el-radio>
              <el-radio label="session">会话知识（仅当前对话可见）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="knowledgeUploadScope === 'session'" label="目标会话">
            <el-select v-model="knowledgeUploadSessionId" placeholder="选择会话" style="width: 100%">
              <el-option
                v-for="s in sessions"
                :key="s.id"
                :label="s.title"
                :value="s.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="选择文件">
            <input type="file" @change="handleKnowledgeUpload" :disabled="knowledgeUploading" />
            <div v-if="knowledgeUploading" style="margin-top: 8px; color: var(--el-color-primary);">
              正在上传并解析文件...
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="knowledgeUploadDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="knowledgeUploadDialogVisible = false">完成</el-button>
        </template>
      </el-dialog>

      <div v-if="!currentSessionId && currentView !== 'knowledge'" class="empty-chat">
        <div class="empty-chat-content">
          <div class="empty-chip-row">
            <span class="empty-chip">Hybrid RAG</span>
            <span class="empty-chip">Multi-Model</span>
            <span class="empty-chip">Streaming</span>
          </div>
          <div class="empty-ai-icon">
            <svg viewBox="0 0 120 120" width="120" height="120">
              <defs>
                <linearGradient id="emptyGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" style="stop-color:#818cf8" />
                  <stop offset="100%" style="stop-color:#c084fc" />
                </linearGradient>
              </defs>
              <circle cx="60" cy="60" r="55" fill="none" stroke="url(#emptyGrad)" stroke-width="1" opacity="0.3" class="pulse-ring" />
              <circle cx="60" cy="60" r="40" fill="none" stroke="url(#emptyGrad)" stroke-width="1" opacity="0.5" class="pulse-ring delay" />
              <circle cx="60" cy="60" r="12" fill="url(#emptyGrad)" opacity="0.8" />
              <circle cx="60" cy="60" r="6" fill="#fff" />
            </svg>
          </div>
          <div class="empty-chat-title">开始你的 AI 之旅</div>
          <div class="empty-chat-desc">把对话、知识、模型治理放进同一个工作台，直接开始一轮更像正式产品的 AI 协作。</div>
          <div class="workspace-overview">
            <div class="workspace-metric">
              <span>会话数</span>
              <strong>{{ sessions.length }}</strong>
            </div>
            <div class="workspace-metric">
              <span>模型数</span>
              <strong>{{ models.length }}</strong>
            </div>
            <div class="workspace-metric">
              <span>已用 Token</span>
              <strong>{{ userProfile.totalTokens || 0 }}</strong>
            </div>
          </div>
          <div class="quick-actions">
            <div v-for="card in quickActionCards" :key="card.title" class="quick-card" @click="quickChat(card.prompt)">
              <div class="quick-icon">{{ card.icon }}</div>
              <span class="quick-title">{{ card.title }}</span>
              <span class="quick-desc">{{ card.desc }}</span>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="chat-messages" ref="messagesRef">
        <div
          v-for="(msg, index) in messages"
          :key="msg.id"
          :class="['message-row', msg.role]"
        >
          <div :class="['message-avatar', msg.role === 'user' ? 'user-avatar' : 'ai-avatar']">
            <template v-if="msg.role === 'user'">
              <img v-if="userProfile.avatarUrl" :src="userProfile.avatarUrl" class="avatar-img-small" />
              <span v-else>{{ (userProfile.nickname || username).charAt(0).toUpperCase() }}</span>
            </template>
            <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
            </svg>
          </div>
          <div class="message-content">
            <div class="message-meta">
              <span class="message-role">{{ msg.role === 'user' ? userDisplayName : 'AI Assistant' }}</span>
              <span class="message-meta-dot"></span>
              <span class="message-role-sub">{{ msg.role === 'user' ? '提问 / 指令' : currentModel }}</span>
            </div>
            <div v-if="editingMessageId === msg.id" class="message-edit-area">
              <el-input
                v-model="editingMessageContent"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 10 }"
              />
              <div class="message-edit-actions">
                <el-button size="small" @click="cancelEditMessage">取消</el-button>
                <el-button size="small" type="primary" @click="saveEditedMessage(msg.id, index)">保存并重新生成</el-button>
              </div>
            </div>
            <div v-else v-html="renderMarkdown(msg.content)"></div>
            
            <div v-if="msg.role === 'assistant' && editingMessageId !== msg.id" class="message-actions">
              <el-button size="small" text @click="copyText(msg.content)" :icon="CopyDocument">复制</el-button>
              <el-button size="small" text :type="msg.feedback === 'like' ? 'primary' : 'default'" @click="handleFeedback(msg, 'like')">👍</el-button>
              <el-button size="small" text :type="msg.feedback === 'dislike' ? 'danger' : 'default'" @click="handleFeedback(msg, 'dislike')">👎</el-button>
            </div>
            <div v-if="msg.role === 'user' && editingMessageId !== msg.id" class="message-actions">
              <el-button size="small" text @click="startEditMessage(msg)" :icon="Edit">编辑</el-button>
              <el-button size="small" text @click="handleRegenerateMessage(msg, index)" :icon="RefreshRight">重新生成</el-button>
            </div>
          </div>
        </div>
        <div v-if="isStreaming" class="message-row assistant">
          <div class="message-avatar ai-avatar">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
            </svg>
          </div>
          <div class="message-content">
            <div class="message-meta">
              <span class="message-role">AI Assistant</span>
              <span class="message-meta-dot"></span>
              <span class="message-role-sub">{{ currentModel }}</span>
            </div>
            <div v-if="queueStatusText" class="queue-status-banner">{{ queueStatusText }}</div>
            <div v-if="streamingReasoningText" class="reasoning-container" :class="{ 'reasoning-done': reasoningFinished }">
              <div class="reasoning-header" @click="reasoningExpanded = !reasoningExpanded">
                <span class="reasoning-icon">{{ reasoningFinished ? '💭' : '⏳' }}</span>
                <span class="reasoning-title">{{ reasoningFinished ? '已完成深度思考' : '正在思考中...' }}</span>
                <span class="reasoning-toggle">{{ reasoningExpanded ? '▾' : '▸' }}</span>
              </div>
              <div v-if="reasoningExpanded" class="reasoning-content" v-html="renderedReasoningHtml"></div>
              <div v-else class="reasoning-collapsed" v-html="renderedReasoningPreview"></div>
            </div>
            <div v-if="renderedHtml" v-html="renderedHtml"></div>
            <span v-if="isStreaming && renderedHtml" class="streaming-cursor"></span>
            <div v-if="!streamingText && !streamingReasoningText" class="typing-indicator">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input-area">
        <div v-if="pendingAttachments.length > 0" class="attachment-preview-area">
          <div v-for="(file, index) in pendingAttachments" :key="index" class="attachment-tag">
            <span class="attachment-name">📎 {{ file.fileName }}</span>
            <span v-if="!isStreaming" class="attachment-summarize-btn" @click="summarizeAttachment(index)">AI总结</span>
            <el-icon class="remove-attachment" @click="removeAttachment(index)"><Close /></el-icon>
          </div>
          <div v-if="summarizeProgress" class="summarize-progress">{{ summarizeProgress }}</div>
        </div>
        <div class="composer-status-row">
          <div class="composer-status-group">
            <span class="composer-chip composer-chip-primary">模型 {{ currentModel }}</span>
            <span v-if="hasManualSessionMode" class="composer-chip">会话模式 {{ currentSessionSkillName }}</span>
            <span v-if="hasTemporaryMode" class="composer-chip composer-chip-accent">本轮模式 {{ temporarySkillName }}</span>
            <span v-if="promptTemplates.length > 0" class="composer-chip">模板 {{ promptTemplates.length }}</span>
            <span v-if="pendingAttachments.length > 0" class="composer-chip composer-chip-accent">附件 {{ pendingAttachments.length }}</span>
          </div>
          <span class="composer-status-text">{{ currentSessionId ? '当前会话已就绪，可直接提问或上传资料' : '创建首个会话后即可开始对话' }}</span>
        </div>
        <div class="input-tools">
          <el-select
            v-if="skills.length > 0 && !currentSessionId"
            v-model="newSessionSkillId"
            size="small"
            class="skill-select composer-skill-select"
            placeholder="新会话模式"
          >
            <el-option label="自动模式（推荐）" value="" />
            <el-option v-for="skill in skills" :key="skill.id" :label="skill.name" :value="skill.id" />
          </el-select>
          <el-select
            v-if="skills.length > 0"
            v-model="temporarySkillId"
            size="small"
            class="skill-select composer-skill-select"
            placeholder="本轮模式"
          >
            <el-option label="跟随会话 / 自动（推荐）" value="" />
            <el-option v-for="skill in skills" :key="skill.id" :label="skill.name" :value="skill.id" />
          </el-select>
          <el-dropdown trigger="click" placement="top-start" @command="applyTemplate" v-if="promptTemplates && promptTemplates.length > 0">
            <el-button size="small" type="primary" text class="input-tool-btn">
              快捷指令 <span class="dropdown-arrow">▾</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="t in promptTemplates" :key="t.id" :command="t.content">
                  {{ t.title }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button size="small" type="primary" text class="input-tool-btn" @click="fileInput.click()" :loading="isUploading">
            <el-icon><Paperclip /></el-icon> 上传文件
          </el-button>
          <input type="file" ref="fileInput" style="display: none" @change="handleFileUpload" />
        </div>
        <div class="input-wrapper">
          <el-input
            v-model="inputText"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            :placeholder="currentInputPlaceholder"
            @keydown.enter.exact.prevent="sendMessage"
            :disabled="isStreaming"
          />
          <el-button
            v-if="!isStreaming"
            class="send-btn"
            type="primary"
            @click="sendMessage"
            :disabled="!inputText.trim()"
            :icon="Promotion"
          />
          <el-button
            v-else
            class="stop-btn"
            type="danger"
            @click="stopStreaming()"
            :icon="Refresh"
          >
            停止
          </el-button>
        </div>
        <div class="input-footer">
          <span>AI 可能会产生不准确的信息，请注意甄别</span>
          <span v-if="isStreaming" class="streaming-hint">
            <span v-if="isReconnecting" class="reconnecting-badge">重连中...</span>
            <span v-else>AI 正在思考中...</span>
          </span>
          <span v-else-if="inputText.length > 0" class="char-count">{{ inputText.length }} 字</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { Plus, RefreshRight, CopyDocument, Promotion, Delete, Edit, Download, Refresh, Search, Expand, Upload, Paperclip, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { login, register, createSession, listSessions, deleteSession, listMessages, listModels, regenerate, renameSession, updateSystemPrompt, updateMessage, deleteAfterMessage, searchSessions, searchMessages, getUserProfile, updateUserProfile, uploadAvatar, updateFeedback, listTags, createTag, deleteTag, addTagToSession, removeTagFromSession, listTemplates, uploadFile, listSkills, updateSessionSkill, uploadToKnowledgeBase, listKnowledgeFiles, deleteKnowledgeFile } from './chatApi'
import { renderMarkdown, escapeHtml } from './utils/markdown'

const DEBOUNCE_DELAY = 300
const MAX_RECONNECT_ATTEMPTS = 3
const RECONNECT_DELAY = 2000

function debounce(fn, delay) {
  let timer = null
  return function(...args) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => fn.apply(this, args), delay)
  }
}

const reconnectAttempts = ref(0)
const isReconnecting = ref(false)
const pendingMessage = ref(null)
const editingSessionId = ref(null)
const editingTitle = ref('')
const editInputRef = ref(null)
const fileInput = ref(null)

const pendingAttachments = ref([])
const isUploading = ref(false)
const summarizeProgress = ref('')

const authFeatureItems = [
  '结构化 RAG 检索链路',
  '多模型智能调度',
  '流式对话与状态感知',
  '长会话摘要与知识记忆'
]

const quickActionCards = [
  { icon: '💻', title: '代码诊断', desc: '排查问题并给出优化建议', prompt: '帮我审查这段Java代码的并发与异常处理问题。' },
  { icon: '📚', title: '知识问答', desc: '基于上下文给出可追溯答案', prompt: '解释一下什么是 Spring Boot，以及它和 Spring 的关系。' },
  { icon: '✉️', title: '内容起草', desc: '快速生成正式文本与邮件', prompt: '帮我写一封专业但简洁的工作邮件。' },
  { icon: '📈', title: '方案分析', desc: '输出结论、风险和实施路径', prompt: '分析一下当前 AI 应用落地时最常见的三个工程问题。' }
]

const isLoggedIn = ref(!!localStorage.getItem('token'))
const isRegister = ref(false)
const loginForm = ref({ username: '', password: '' })
const registerForm = ref({ username: '', password: '', confirmPassword: '' })
const loginLoading = ref(false)
const username = ref(localStorage.getItem('username') || 'User')

const sessions = ref([])
const currentSessionId = ref(null)
const currentModel = ref('glm-4.7')
const models = ref([])
const messages = ref([])
const inputText = ref('')
const isStreaming = ref(false)
const streamingText = ref('')
const renderedHtml = ref('')
const streamingReasoningText = ref('')
const renderedReasoningHtml = ref('')
const renderedReasoningPreview = ref('')
const reasoningExpanded = ref(false)
const reasoningFinished = ref(false)
let reasoningRenderTimer = null
let renderThrottleTimer = null
const RENDER_THROTTLE_MS = 80
const queueStatus = ref(null)
const messagesRef = ref(null)
const skills = ref([])
const sessionSkillId = ref('')
const temporarySkillId = ref('')
const newSessionSkillId = ref('')
const AUTO_MODE_LABEL = '自动模式'

const promptDialogVisible = ref(false)
const tempSystemPrompt = ref('')
const savingPrompt = ref(false)

const editingMessageId = ref(null)
const editingMessageContent = ref('')

const searchKeyword = ref('')
const searchResults = ref([])
const isSearching = ref(false)

const profileDialogVisible = ref(false)
const savingProfile = ref(false)
const userProfile = ref({
  nickname: '',
  avatarUrl: '',
  bio: '',
  theme: 'dark'
})
const avatarInput = ref(null)

const allTags = ref([])
const filterTagId = ref('')

const manageTagsDialogVisible = ref(false)
const newTagName = ref('')
const newTagColor = ref('#818cf8')

const sessionTagsDialogVisible = ref(false)
const savingSessionTags = ref(false)
const activeSessionForTags = ref(null)
const currentSessionTagIds = ref([])

const filteredSessions = computed(() => {
  if (!filterTagId.value) return sessions.value
  return sessions.value.filter(s => s.tags && s.tags.some(t => t.id === filterTagId.value))
})

const promptTemplates = ref([])
const mobileSidebarVisible = ref(false)

const currentView = ref('chat')
const knowledgeFiles = ref([])
const knowledgePagination = ref({ current: 1, size: 10, total: 0, pages: 0 })
const knowledgeScopeStats = ref({})
const knowledgeSearchKeyword = ref('')
const knowledgeUploadDialogVisible = ref(false)
const knowledgeUploadScope = ref('user')
const knowledgeUploadSessionId = ref(null)
const knowledgeUploading = ref(false)
const knowledgeLoading = ref(false)

let activeAbortController = null

const currentSession = computed(() => sessions.value.find(session => session.id === currentSessionId.value) || null)

const currentSessionTitle = computed(() => currentSession.value ? currentSession.value.title : 'AI Chat Workspace')
const currentSessionSkillName = computed(() => skillNameById(sessionSkillId.value || currentSession.value?.skillId, AUTO_MODE_LABEL))
const temporarySkillName = computed(() => skillNameById(temporarySkillId.value, ''))
const hasManualSessionMode = computed(() => !!(sessionSkillId.value || currentSession.value?.skillId))
const hasTemporaryMode = computed(() => !!temporarySkillId.value)
const currentInputPlaceholder = computed(() => {
  const activeSkillId = temporarySkillId.value || sessionSkillId.value || currentSession.value?.skillId || newSessionSkillId.value
  if (!activeSkillId) {
    return '输入消息，系统会自动选择最合适的模式，Enter 发送，Shift+Enter 换行，Esc 停止生成'
  }
  const skill = skills.value.find(item => item.id === activeSkillId)
  return skill?.placeholder || '输入消息，Enter 发送，Shift+Enter 换行，Esc 停止生成'
})

const userDisplayName = computed(() => userProfile.value.nickname || username.value)

const sessionStats = computed(() => {
  const total = messages.value.length
  const userCount = messages.value.filter(message => message.role === 'user').length
  const assistantCount = messages.value.filter(message => message.role === 'assistant').length
  return { total, userCount, assistantCount }
})

const headerInsights = computed(() => [
  { label: '消息', value: sessionStats.value.total },
  { label: '用户', value: sessionStats.value.userCount },
  { label: '回复', value: sessionStats.value.assistantCount }
])

async function loadTemplates() {
  try {
    const res = await listTemplates()
    if (res.code === 200) {
      promptTemplates.value = res.data || []
    }
  } catch (e) {
    console.error('Failed to load templates:', e)
  }
}

async function loadWorkspaceData() {
  await Promise.all([
    loadProfile(),
    loadSessions(),
    loadModels(),
    loadAllTags(),
    loadTemplates(),
    loadSkills()
  ])
}

function applyTemplate(content) {
  inputText.value = content
}

async function loadAllTags() {
  try {
    const res = await listTags()
    if (res.code === 200) allTags.value = res.data
  } catch (e) {}
}

async function loadSkills() {
  try {
    const res = await listSkills()
    if (res.code === 200) {
      skills.value = res.data || []
    }
  } catch (e) {}
}

async function handleAddTag() {
  if (!newTagName.value.trim()) return
  try {
    const res = await createTag({ name: newTagName.value.trim(), color: newTagColor.value })
    if (res.code === 200) {
      allTags.value.unshift(res.data)
      newTagName.value = ''
      ElMessage.success('添加成功')
    }
  } catch (e) {
    ElMessage.error('添加失败')
  }
}

async function handleDeleteTag(tagId) {
  try {
    await deleteTag(tagId)
    allTags.value = allTags.value.filter(t => t.id !== tagId)
    sessions.value.forEach(s => {
      if (s.tags) s.tags = s.tags.filter(t => t.id !== tagId)
    })
    if (filterTagId.value === tagId) filterTagId.value = ''
    ElMessage.success('已删除')
  } catch (e) {}
}

function openSessionTags(session) {
  activeSessionForTags.value = session
  currentSessionTagIds.value = (session.tags || []).map(t => t.id)
  sessionTagsDialogVisible.value = true
}

async function saveSessionTags() {
  savingSessionTags.value = true
  const s = activeSessionForTags.value
  const oldIds = (s.tags || []).map(t => t.id)
  const newIds = currentSessionTagIds.value
  const toAdd = newIds.filter(id => !oldIds.includes(id))
  const toRemove = oldIds.filter(id => !newIds.includes(id))

  try {
    for (const id of toAdd) {
      await addTagToSession(s.id, id)
    }
    for (const id of toRemove) {
      await removeTagFromSession(s.id, id)
    }
    s.tags = allTags.value.filter(t => newIds.includes(t.id))
    sessionTagsDialogVisible.value = false
    ElMessage.success('标签已更新')
  } catch (e) {
    ElMessage.error('保存失败')
  }
  savingSessionTags.value = false
}

async function loadProfile() {
  try {
    const res = await getUserProfile()
    if (res.code === 200 && res.data) {
      userProfile.value = res.data
      applyTheme(userProfile.value.theme)
    }
  } catch (e) {
    console.error(e)
  }
}

function applyTheme(theme) {
  document.documentElement.classList.toggle('light', theme === 'light')
  document.documentElement.classList.toggle('dark', theme !== 'light')
}

function openProfileDialog() {
  profileDialogVisible.value = true
}

async function saveProfile() {
  savingProfile.value = true
  try {
    const res = await updateUserProfile(userProfile.value)
    if (res.code === 200) {
      ElMessage.success('个人设置已保存')
      profileDialogVisible.value = false
      applyTheme(userProfile.value.theme)
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch (e) {
    ElMessage.error('保存失败')
  }
  savingProfile.value = false
}

function triggerAvatarUpload() {
  if (avatarInput.value) {
    avatarInput.value.click()
  }
}

async function handleAvatarUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 2MB')
    return
  }
  const formData = new FormData()
  formData.append('file', file)
  try {
    const res = await uploadAvatar(formData)
    if (res.code === 200) {
      userProfile.value.avatarUrl = res.data
      ElMessage.success('头像上传成功')
    } else {
      ElMessage.error(res.msg || '上传失败')
    }
  } catch (err) {
    ElMessage.error('上传失败')
  }
  e.target.value = ''
}

async function handleFileUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }
  isUploading.value = true
  try {
    const res = await uploadFile(file, currentSessionId.value)
    if (res.code === 200) {
      pendingAttachments.value.push(res.data)
      ElMessage.success('文件解析成功')
    } else {
      ElMessage.error(res.msg || '文件解析失败')
    }
  } catch (error) {
    console.error('文件上传失败:', error)
    ElMessage.error('文件上传失败，请稍后重试')
  } finally {
    isUploading.value = false
    e.target.value = ''
  }
}

function removeAttachment(index) {
  pendingAttachments.value.splice(index, 1)
}

async function summarizeAttachment(index) {
  const file = pendingAttachments.value[index]
  if (!file || !file.id || !currentSessionId.value) return
  if (isStreaming.value) return

  const fileName = file.fileName
  pendingAttachments.value.splice(index, 1)
  summarizeProgress.value = ''

  messages.value.push({ id: Date.now(), sessionId: currentSessionId.value, role: 'user', content: '请总结文件：' + fileName })
  nextTick(scrollToBottom)

  isStreaming.value = true
  streamingText.value = ''
  streamingReasoningText.value = ''
  reasoningExpanded.value = false
  reasoningFinished.value = false
  queueStatus.value = null

  const token = localStorage.getItem('token')
  try {
    activeAbortController?.abort()
    activeAbortController = new AbortController()
    const response = await fetch('/chat/summarize-file', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: currentSessionId.value,
        attachmentId: file.id,
        modelName: currentModel.value
      }),
      signal: activeAbortController.signal
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    await consumeSseStream(response)
  } catch (err) {
    if (err.name !== 'AbortError') {
      ElMessage.error('文件总结失败: ' + err.message)
    }
    resetStreamingState()
  }
}

const debouncedSearch = debounce(async () => {
  if (!searchKeyword.value.trim()) {
    searchResults.value = []
    return
  }
  isSearching.value = true
  try {
    const keyword = searchKeyword.value.trim()
    const [sessRes, msgRes] = await Promise.all([
      searchSessions(keyword),
      searchMessages(keyword)
    ])
    const results = []
    if (sessRes.code === 200) {
      sessRes.data.forEach(s => {
        results.push({
          id: 's_' + s.id,
          sessionId: s.id,
          sessionTitle: s.title,
          matchContent: null
        })
      })
    }
    if (msgRes.code === 200) {
      msgRes.data.forEach(m => {
        const s = sessions.value.find(sess => sess.id === m.sessionId)
        results.push({
          id: 'm_' + m.id,
          sessionId: m.sessionId,
          sessionTitle: s ? s.title : '未知对话',
          matchContent: m.content
        })
      })
    }
    searchResults.value = results
  } catch (e) {
    console.error(e)
  }
  isSearching.value = false
}, 500)

function highlightKeyword(text) {
  if (!text) return ''
  const keyword = searchKeyword.value.trim()
  if (!keyword) return escapeHtml(text)
  const regex = new RegExp(`(${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
  return escapeHtml(text).replace(regex, '<span class="highlight-keyword">$1</span>')
}

function jumpToSession(sessionId) {
  searchKeyword.value = ''
  searchResults.value = []
  switchSession(sessionId)
}

function openPromptDialog() {
  tempSystemPrompt.value = currentSession.value?.systemPrompt || ''
  promptDialogVisible.value = true
}

async function saveSystemPrompt() {
  if (!currentSessionId.value) return
  savingPrompt.value = true
  try {
    const res = await updateSystemPrompt(currentSessionId.value, tempSystemPrompt.value)
    if (res.code === 200) {
      const s = sessions.value.find(s => s.id === currentSessionId.value)
      if (s) s.systemPrompt = tempSystemPrompt.value
      ElMessage.success('保存成功')
      promptDialogVisible.value = false
    } else {
      ElMessage.error(res.msg)
    }
  } catch (e) {
    ElMessage.error('保存失败')
  }
  savingPrompt.value = false
}

const particleCanvas = ref(null)
let animationFrameId = null
let particles = []

const queueStatusText = computed(() => {
  const status = queueStatus.value
  if (!status || !status.message) return ''
  const waiting = Number.isFinite(Number(status.waitingRequests)) ? Number(status.waitingRequests) : 0
  const waitedMs = Number.isFinite(Number(status.waitedMs)) ? Number(status.waitedMs) : 0

  if (status.phase === 'queued' && waiting > 0) {
    return `${status.message}，前方还有 ${waiting} 个请求`
  }
  if (status.phase === 'acquired' && waitedMs > 0) {
    return `${status.message}，排队约 ${waitedMs}ms`
  }
  return status.message
})

class Particle {
  constructor(canvas) {
    this.canvas = canvas
    this.x = Math.random() * canvas.width
    this.y = Math.random() * canvas.height
    this.size = Math.random() * 1.5 + 0.5
    this.speedX = (Math.random() - 0.5) * 0.4
    this.speedY = (Math.random() - 0.5) * 0.4
    this.opacity = Math.random() * 0.35 + 0.1
  }
  update() {
    this.x += this.speedX
    this.y += this.speedY
    if (this.x < 0 || this.x > this.canvas.width) this.speedX *= -1
    if (this.y < 0 || this.y > this.canvas.height) this.speedY *= -1
  }
  draw(ctx) {
    ctx.beginPath()
    ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(129, 140, 248, ${this.opacity})`
    ctx.fill()
  }
}

function initParticles() {
  const canvas = particleCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  canvas.width = window.innerWidth
  canvas.height = window.innerHeight

  particles = []
  const count = Math.min(35, Math.floor((canvas.width * canvas.height) / 35000))
  for (let i = 0; i < count; i++) {
    particles.push(new Particle(canvas))
  }

  const maxDist = 100
  const maxDistSq = maxDist * maxDist

  function animate() {
    ctx.fillStyle = 'rgba(10, 10, 26, 0.15)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    
    const len = particles.length
    for (let i = 0; i < len; i++) {
      const p = particles[i]
      p.update()
      p.draw(ctx)
      
      for (let j = i + 1; j < len; j++) {
        const p2 = particles[j]
        const dx = p.x - p2.x
        const dy = p.y - p2.y
        const distSq = dx * dx + dy * dy
        if (distSq < maxDistSq) {
          const alpha = 0.1 * (1 - distSq / maxDistSq)
          ctx.beginPath()
          ctx.strokeStyle = `rgba(129, 140, 248, ${alpha})`
          ctx.lineWidth = 0.5
          ctx.moveTo(p.x, p.y)
          ctx.lineTo(p2.x, p2.y)
          ctx.stroke()
        }
      }
    }
    animationFrameId = requestAnimationFrame(animate)
  }
  animate()
}

function resizeCanvas() {
  if (particleCanvas.value) {
    particleCanvas.value.width = window.innerWidth
    particleCanvas.value.height = window.innerHeight
  }
}

async function handleLogin() {
  if (!loginForm.value.username) { ElMessage.warning('请输入用户名'); return }
  if (!loginForm.value.password) { ElMessage.warning('请输入密码'); return }
  loginLoading.value = true
  try {
    const res = await login(loginForm.value)
    if (res.code === 200) {
      localStorage.setItem('token', res.data.token)
      localStorage.setItem('userId', res.data.userId)
      localStorage.setItem('username', res.data.username)
      username.value = res.data.username
      isLoggedIn.value = true
      loadWorkspaceData()
      ElMessage.success('登录成功')
    } else {
      ElMessage.error(res.msg)
    }
  } catch (e) {
    ElMessage.error('登录失败')
  }
  loginLoading.value = false
}

async function handleRegister() {
  if (!registerForm.value.username) { ElMessage.warning('请输入用户名'); return }
  if (!registerForm.value.password) { ElMessage.warning('请输入密码'); return }
  if (registerForm.value.password !== registerForm.value.confirmPassword) {
    ElMessage.warning('两次密码不一致'); return
  }
  loginLoading.value = true
  try {
    const res = await register({ username: registerForm.value.username, password: registerForm.value.password })
    if (res.code === 200) {
      ElMessage.success('注册成功，请登录')
      isRegister.value = false
      loginForm.value.username = registerForm.value.username
      registerForm.value = { username: '', password: '', confirmPassword: '' }
    } else {
      ElMessage.error(res.msg)
    }
  } catch (e) {
    ElMessage.error('注册失败')
  }
  loginLoading.value = false
}

function handleLogout() {
  ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' }).then(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')
    isLoggedIn.value = false
    sessions.value = []
    messages.value = []
    currentSessionId.value = null
    ElMessage.success('已退出')
  }).catch(() => {})
}

async function loadSessions() {
  try {
    const res = await listSessions()
    if (res.code === 200) {
      sessions.value = res.data
      if (currentSessionId.value) {
        const current = res.data.find(item => item.id === currentSessionId.value)
        sessionSkillId.value = current?.skillId || ''
      }
    }
  } catch (e) {}
}

async function loadModels() {
  try {
    const res = await listModels()
    if (res.code === 200) {
      models.value = res.data
      if (models.value.length > 0 && !currentModel.value) {
        currentModel.value = models.value[0].modelName
      }
    }
  } catch (e) {}
}

async function createNewSession() {
  try {
    const res = await createSession({ title: '新对话', skillId: newSessionSkillId.value || null })
    if (res.code === 200) {
      currentView.value = 'chat'
      sessions.value.unshift(res.data)
      currentSessionId.value = res.data.id
      sessionSkillId.value = res.data.skillId || ''
      temporarySkillId.value = ''
      messages.value = []
      inputText.value = ''
    }
  } catch (e) {}
}

async function switchSession(sessionId) {
  currentView.value = 'chat'
  currentSessionId.value = sessionId
  mobileSidebarVisible.value = false
  sessionSkillId.value = sessions.value.find(session => session.id === sessionId)?.skillId || ''
  temporarySkillId.value = ''
  messages.value = []
  const draft = localStorage.getItem('draft_' + sessionId)
  if (draft) {
    inputText.value = draft
  } else {
    inputText.value = ''
  }
  try {
    const res = await listMessages(sessionId)
    if (res.code === 200) {
      messages.value = res.data
      nextTick(scrollToBottom)
    }
  } catch (e) {}
}

async function handleDeleteSession(sessionId) {
  try {
    await ElMessageBox.confirm('确定删除该对话吗？', '提示', { type: 'warning' })
    await deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
      sessionSkillId.value = ''
      temporarySkillId.value = ''
      messages.value = []
    }
    ElMessage.success('已删除')
  } catch (e) {}
}

function startEditSession(session) {
  editingSessionId.value = session.id
  editingTitle.value = session.title
  nextTick(() => {
    if (editInputRef.value) editInputRef.value.focus()
  })
}

async function finishEditSession(sessionId) {
  if (editingTitle.value.trim() && editingTitle.value.trim() !== sessions.value.find(s => s.id === sessionId)?.title) {
    try {
      await renameSession(sessionId, editingTitle.value.trim())
      const session = sessions.value.find(s => s.id === sessionId)
      if (session) session.title = editingTitle.value.trim()
      ElMessage.success('已重命名')
    } catch (e) {
      ElMessage.error('重命名失败')
    }
  }
  editingSessionId.value = null
  editingTitle.value = ''
}

function cancelEditSession() {
  editingSessionId.value = null
  editingTitle.value = ''
}

function exportChat(format = 'markdown') {
  if (!currentSessionId.value || messages.value.length === 0) {
    ElMessage.warning('暂无对话内容可导出')
    return
  }
  const session = sessions.value.find(s => s.id === currentSessionId.value)
  const title = session?.title || '对话'
  let content = ''
  let filename = ''
  if (format === 'markdown') {
    content = `# ${title}\n\n导出时间: ${new Date().toLocaleString()}\n\n---\n\n`
    messages.value.forEach(msg => {
      const role = msg.role === 'user' ? '👤 用户' : '🤖 AI'
      content += `### ${role}\n\n${msg.content}\n\n---\n\n`
    })
    filename = `${title}.md`
  } else if (format === 'json') {
    content = JSON.stringify({
      title,
      exportTime: new Date().toISOString(),
      messages: messages.value.map(m => ({ role: m.role, content: m.content }))
    }, null, 2)
    filename = `${title}.json`
  } else if (format === 'txt') {
    content = `${title}\n导出时间: ${new Date().toLocaleString()}\n${'='.repeat(40)}\n\n`
    messages.value.forEach(msg => {
      const role = msg.role === 'user' ? '用户' : 'AI'
      content += `[${role}]\n${msg.content}\n\n`
    })
    filename = `${title}.txt`
  }
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('导出成功')
}

function handleClearMessages() {
  ElMessageBox.confirm('确定清空当前对话的所有消息吗？', '提示', { type: 'warning' }).then(() => {
    messages.value = []
    ElMessage.success('已清空')
  }).catch(() => {})
}

async function quickChat(text) {
  if (!currentSessionId.value) {
    try {
      const res = await createSession({
        title: text.length > 20 ? text.substring(0, 20) + '...' : text,
        skillId: newSessionSkillId.value || null
      })
      if (res.code === 200) {
        currentView.value = 'chat'
        sessions.value.unshift(res.data)
        currentSessionId.value = res.data.id
        sessionSkillId.value = res.data.skillId || ''
        temporarySkillId.value = ''
        messages.value = []
      } else {
        ElMessage.error(res.msg || '创建会话失败')
        return
      }
    } catch (e) {
      ElMessage.error('创建会话失败')
      return
    }
  }
  inputText.value = text
  sendMessage()
}

function sendMessage() {
  if (!inputText.value.trim() && pendingAttachments.value.length === 0) return
  if (isStreaming.value) return

  if (!currentSessionId.value) {
    let initialText = inputText.value.trim() || '分析文件'
    quickChat(initialText)
    return
  }

  let text = inputText.value.trim()
  if (pendingAttachments.value.length > 0) {
    const attachmentStr = pendingAttachments.value.map(a => `📎 **${a.fileName}**`).join('、')
    if (text) {
      text = `[已上传文件：${attachmentStr}]\n\n${text}`
    } else {
      text = `[已上传文件：${attachmentStr}]\n\n请帮我总结一下文件内容。`
    }
    pendingAttachments.value = []
  }

  inputText.value = ''
  messages.value.push({ id: Date.now(), sessionId: currentSessionId.value, role: 'user', content: text })
  nextTick(scrollToBottom)
  startStream(text)
}

async function startStream(text, isRetry = false) {
  if (!currentSessionId.value) return
  isStreaming.value = true
  streamingText.value = ''
  streamingReasoningText.value = ''
  reasoningExpanded.value = false
  reasoningFinished.value = false
  queueStatus.value = null
  if (!isRetry) {
    pendingMessage.value = text
    reconnectAttempts.value = 0
  }
  const token = localStorage.getItem('token')
  const params = new URLSearchParams()
  if (currentSessionId.value) params.append('sessionId', String(currentSessionId.value))
  params.append('content', text)
  if (currentModel.value) params.append('modelName', currentModel.value)
  if (temporarySkillId.value) params.append('skillId', temporarySkillId.value)

  try {
    activeAbortController?.abort()
    activeAbortController = new AbortController()
    const response = await fetch(`/chat/stream?${params.toString()}`, {
      headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'text/event-stream' },
      signal: activeAbortController.signal
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    isReconnecting.value = false
    reconnectAttempts.value = 0
    await consumeSseStream(response)
  } catch (err) {
    if (err.name === 'AbortError') {
      resetStreamingState()
      return
    }
    if (reconnectAttempts.value < MAX_RECONNECT_ATTEMPTS && pendingMessage.value) {
      reconnectAttempts.value++
      isReconnecting.value = true
      ElMessage.warning(`连接断开，正在尝试重连 (${reconnectAttempts.value}/${MAX_RECONNECT_ATTEMPTS})...`)
      setTimeout(() => {
        if (pendingMessage.value && isStreaming.value) {
          startStream(pendingMessage.value, true)
        }
      }, RECONNECT_DELAY)
    } else {
      ElMessage.error('连接失败: ' + err.message + '，请稍后重试')
      resetStreamingState()
    }
  }
}

function finishStream() {
  const finalContent = streamingText.value || streamingReasoningText.value
  if (finalContent) {
    messages.value.push({ id: Date.now(), sessionId: currentSessionId.value, role: 'assistant', content: finalContent })
  }
  if (streamingReasoningText.value) {
    reasoningFinished.value = true
    reasoningExpanded.value = false
    scheduleReasoningRender()
  }
  resetStreamingState()
  nextTick(scrollToBottom)
  loadSessions()
  loadProfile()
}

function resetStreamingState() {
  streamingText.value = ''
  streamingReasoningText.value = ''
  renderedHtml.value = ''
  renderedReasoningHtml.value = ''
  renderedReasoningPreview.value = ''
  reasoningExpanded.value = false
  reasoningFinished.value = false
  if (renderThrottleTimer) {
    clearTimeout(renderThrottleTimer)
    renderThrottleTimer = null
  }
  if (reasoningRenderTimer) {
    clearTimeout(reasoningRenderTimer)
    reasoningRenderTimer = null
  }
  queueStatus.value = null
  summarizeProgress.value = ''
  isStreaming.value = false
  isReconnecting.value = false
  pendingMessage.value = null
  reconnectAttempts.value = 0
}

function stopStreaming() {
  activeAbortController?.abort()
  activeAbortController = null
  resetStreamingState()
  ElMessage.info('已停止生成')
}

function tryParseJson(text) {
  if (!text || typeof text !== 'string') return null
  const trimmed = text.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
  try {
    return JSON.parse(trimmed)
  } catch {
    return null
  }
}

function handleQueueStatusPayload(payload) {
  if (!payload || payload.type !== 'queue_status') return
  queueStatus.value = payload
}

function handleStreamPayload(eventName, eventData) {
  if (eventData === '[DONE]') {
    finishStream()
    return true
  }
  if (!eventData) {
    return false
  }

  if (eventName === 'reasoning') {
    streamingReasoningText.value += eventData
    reasoningExpanded.value = true
    scheduleReasoningRender()
    nextTick(scrollToBottom)
    return false
  }

  if (eventName === 'queue_status') {
    handleQueueStatusPayload(tryParseJson(eventData))
    nextTick(scrollToBottom)
    return false
  }

  if (eventName === 'summarize_progress') {
    const progressData = tryParseJson(eventData)
    if (progressData && progressData.message) {
      summarizeProgress.value = progressData.message
    }
    nextTick(scrollToBottom)
    return false
  }

  const jsonPayload = tryParseJson(eventData)
  if (jsonPayload?.type === 'queue_status') {
    handleQueueStatusPayload(jsonPayload)
    nextTick(scrollToBottom)
    return false
  }

  if (jsonPayload && typeof jsonPayload === 'object' && jsonPayload.code && jsonPayload.code !== 200) {
    ElMessage.error(jsonPayload.msg || '流式响应失败')
    resetStreamingState()
    return true
  }

  if (streamingReasoningText.value && !reasoningFinished.value) {
    reasoningFinished.value = true
    reasoningExpanded.value = false
    scheduleReasoningRender()
  }

  queueStatus.value = null
  streamingText.value += eventData
  nextTick(scrollToBottom)
  return false
}

async function consumeSseStream(response) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'
  let eventDataBuffer = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      finishStream()
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const rawLine of lines) {
      const line = rawLine.replace(/\r$/, '')
      if (line.trim() === '') {
        if (eventDataBuffer.length > 0) {
          const shouldStop = handleStreamPayload(eventName, eventDataBuffer.join('\n'))
          eventDataBuffer = []
          eventName = 'message'
          if (shouldStop) {
            return
          }
        } else {
          eventName = 'message'
        }
      } else if (line.startsWith(':')) {
        continue
      } else if (line.startsWith('event:')) {
        eventName = line.substring(6).trim() || 'message'
      } else if (line.startsWith('data:')) {
        const data = line.substring(5)
        eventDataBuffer.push(data.startsWith(' ') ? data.substring(1) : data)
      }
    }
  }
}

function handleKeydown(e) {
  if (e.key === 'Escape' && isStreaming.value) {
    stopStreaming()
  }
}

const debouncedSaveDraft = debounce((text) => {
  if (currentSessionId.value && text) {
    localStorage.setItem('draft_' + currentSessionId.value, text)
  }
}, DEBOUNCE_DELAY)

watch(inputText, (val) => {
  debouncedSaveDraft(val)
})

watch(streamingText, (val) => {
  if (renderThrottleTimer) clearTimeout(renderThrottleTimer)
  renderThrottleTimer = setTimeout(() => {
    renderedHtml.value = renderMarkdown(val)
  }, RENDER_THROTTLE_MS)
})

watch(isStreaming, (val) => {
  if (!val) {
    if (renderThrottleTimer) clearTimeout(renderThrottleTimer)
    renderedHtml.value = ''
  }
})

function scheduleReasoningRender() {
  if (reasoningRenderTimer) clearTimeout(reasoningRenderTimer)
  reasoningRenderTimer = setTimeout(() => {
    if (streamingReasoningText.value) {
      renderedReasoningHtml.value = renderMarkdown(streamingReasoningText.value)
      renderedReasoningPreview.value = renderMarkdown(getLastTwoLines(streamingReasoningText.value))
    }
  }, RENDER_THROTTLE_MS)
}

function getLastTwoLines(text) {
  if (!text) return ''
  const lines = text.split('\n')
  const nonEmpty = lines.filter(l => l.trim())
  if (nonEmpty.length <= 2) return nonEmpty.join('\n')
  return '...\n' + nonEmpty.slice(-2).join('\n')
}

function startEditMessage(msg) {
  editingMessageId.value = msg.id
  editingMessageContent.value = msg.content
}

function cancelEditMessage() {
  editingMessageId.value = null
  editingMessageContent.value = ''
}

async function saveEditedMessage(messageId, index) {
  if (!editingMessageContent.value.trim()) {
    ElMessage.warning('消息内容不能为空')
    return
  }
  if (isStreaming.value) {
    ElMessage.warning('请等待当前回复完成')
    return
  }
  
  const newContent = editingMessageContent.value.trim()
  try {
    await updateMessage(messageId, currentSessionId.value, newContent)
    await deleteAfterMessage(currentSessionId.value, messageId)
    
    // Update local state
    messages.value[index].content = newContent
    messages.value.splice(index + 1) // Remove all messages after this one
    
    editingMessageId.value = null
    editingMessageContent.value = ''
    
    // Trigger regeneration
    await doRegenerate()
  } catch (e) {
    ElMessage.error('编辑失败')
  }
}

async function doRegenerate() {
  isStreaming.value = true
  streamingText.value = ''
  streamingReasoningText.value = ''
  reasoningExpanded.value = false
  reasoningFinished.value = false
  queueStatus.value = null
  const token = localStorage.getItem('token')
  try {
    activeAbortController?.abort()
    activeAbortController = new AbortController()
    const response = await fetch('/chat/regenerate', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ sessionId: currentSessionId.value, modelName: currentModel.value, skillId: temporarySkillId.value || null }),
      signal: activeAbortController.signal
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    await consumeSseStream(response)
  } catch (err) {
    if (err.name !== 'AbortError') {
      ElMessage.error('重新生成失败: ' + err.message)
    }
    resetStreamingState()
  }
}

async function handleRegenerateMessage(msg, index) {
  if (isStreaming.value) return
  editingMessageId.value = msg.id
  editingMessageContent.value = msg.content
  await saveEditedMessage(msg.id, index)
}

function copyText(text) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制')).catch(() => ElMessage.error('复制失败'))
}

function skillNameById(skillId, fallbackLabel = AUTO_MODE_LABEL) {
  if (!skillId) return fallbackLabel
  const skill = skills.value.find(item => item.id === skillId)
  return skill?.name || fallbackLabel
}

async function handleUpdateSessionSkill() {
  if (!currentSessionId.value) return
  try {
    const normalizedSkillId = sessionSkillId.value || null
    const res = await updateSessionSkill(currentSessionId.value, normalizedSkillId)
    if (res.code === 200) {
      const session = sessions.value.find(item => item.id === currentSessionId.value)
      if (session) {
        session.skillId = normalizedSkillId
      }
      ElMessage.success('会话模式已更新')
    } else {
      ElMessage.error(res.msg || '更新失败')
    }
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

async function handleFeedback(msg, type) {
  const newFeedback = msg.feedback === type ? null : type
  try {
    await updateFeedback(msg.id, currentSessionId.value, newFeedback)
    msg.feedback = newFeedback
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function scrollToBottom() {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

function switchToKnowledgeView() {
  currentView.value = 'knowledge'
  currentSessionId.value = null
  loadKnowledgeFiles(1)
}

function switchToChatView() {
  currentView.value = 'chat'
}

const debounceLoadKnowledgeFiles = debounce(() => loadKnowledgeFiles(1), DEBOUNCE_DELAY)

async function loadKnowledgeFiles(page = 1) {
  knowledgeLoading.value = true
  try {
    const res = await listKnowledgeFiles({
      current: page,
      size: knowledgePagination.value.size,
      keyword: knowledgeSearchKeyword.value || undefined
    })
    if (res.code === 200 && res.data) {
      knowledgeFiles.value = res.data.records || []
      knowledgePagination.value = {
        current: res.data.current,
        size: res.data.size,
        total: res.data.total,
        pages: res.data.pages
      }
      knowledgeScopeStats.value = res.data.scopeStats || {}
    }
  } catch (e) {
    console.error('加载知识库文件列表失败:', e)
  }
  knowledgeLoading.value = false
}

function openKnowledgeUpload() {
  knowledgeUploadScope.value = 'user'
  knowledgeUploadSessionId.value = null
  knowledgeUploadDialogVisible.value = true
}

async function handleKnowledgeUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 10MB')
    return
  }
  knowledgeUploading.value = true
  try {
    const res = await uploadToKnowledgeBase(file, knowledgeUploadScope.value, knowledgeUploadSessionId.value || undefined)
    if (res.code === 200) {
      ElMessage.success('文件上传并解析成功')
      knowledgeUploadDialogVisible.value = false
      loadKnowledgeFiles(knowledgePagination.value.current)
    } else {
      ElMessage.error(res.msg || '文件上传失败')
    }
  } catch (err) {
    console.error('知识库文件上传失败:', err)
    ElMessage.error('文件上传失败，请稍后重试')
  }
  knowledgeUploading.value = false
  e.target.value = ''
}

async function handleDeleteKnowledgeFile(attachmentId, fileName) {
  try {
    await ElMessageBox.confirm(`确定要删除「${fileName}」吗？删除后所有对话中将无法检索到该文件内容。`, '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const res = await deleteKnowledgeFile(attachmentId)
    if (res.code === 200) {
      ElMessage.success('已删除')
      loadKnowledgeFiles(knowledgePagination.value.current)
    } else {
      ElMessage.error(res.msg || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function scopeLabel(scope) {
  return { session: '会话', user: '用户', global: '全局' }[scope] || scope
}

function scopeTagType(scope) {
  return { session: '', user: 'success', global: 'warning' }[scope] || ''
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  if (isLoggedIn.value) {
    loadWorkspaceData()
  } else {
    initParticles()
    window.addEventListener('resize', resizeCanvas)
  }
})

onBeforeUnmount(() => {
  activeAbortController?.abort()
  window.removeEventListener('keydown', handleKeydown)
  if (animationFrameId) cancelAnimationFrame(animationFrameId)
  window.removeEventListener('resize', resizeCanvas)
})
</script>

<style scoped>
.queue-status-banner {
  margin-bottom: 12px;
  padding: 11px 13px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(129, 140, 248, 0.14), rgba(99, 102, 241, 0.08));
  border: 1px solid rgba(129, 140, 248, 0.24);
  color: #c7d2fe;
  font-size: 13px;
  line-height: 1.55;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.skill-select {
  min-width: 142px;
}

.session-skill-select {
  margin-right: 4px;
}

.composer-skill-select {
  margin-right: 4px;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: rgba(255, 255, 255, 0.42);
  font-size: 11px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.message-role {
  color: rgba(255, 255, 255, 0.92);
  font-weight: 700;
}

.message-role-sub {
  color: rgba(255, 255, 255, 0.46);
}

.message-meta-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
}

.empty-hint {
  text-align: center;
  padding: 20px;
  color: var(--el-text-color-secondary);
}
.message-edit-area {
  width: 100%;
}
.message-edit-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.sidebar-search {
  padding: 0 16px 12px;
}
.search-results {
  flex: 1;
  overflow-y: auto;
  padding: 0 16px;
}
.search-loading {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 20px 0;
  font-size: 13px;
}
.search-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.search-item {
  padding: 10px;
  border-radius: 8px;
  background-color: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  transition: all 0.2s;
}
.search-item:hover {
  background-color: rgba(255, 255, 255, 0.08);
}
.search-item-title {
  font-weight: 500;
  color: var(--el-text-color-primary);
  margin-bottom: 4px;
  font-size: 14px;
}
.search-item-content {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.4;
}
:deep(.highlight-keyword) {
  color: #818cf8;
  font-weight: bold;
}
.avatar-uploader {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: 1px dashed var(--el-border-color);
  cursor: pointer;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
}
.avatar-uploader:hover {
  border-color: var(--el-color-primary);
}
.avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar-placeholder {
  width: 100%;
  height: 100%;
  background-color: var(--el-fill-color-light);
  display: flex;
  justify-content: center;
  align-items: center;
  color: var(--el-text-color-secondary);
  font-size: 24px;
}
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}
.avatar-img-small {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}

.sidebar-tags {
  padding: 0 16px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.tag-filter-select {
  flex: 1;
}
.tag-color-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
}
.session-title-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}
.session-tags {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}
.session-tag-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.session-tag-btn {
  padding: 4px;
  border-radius: 4px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
}
.session-tag-btn:hover {
  background-color: var(--el-fill-color);
  color: var(--el-text-color-primary);
}

.tag-checkbox-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.manage-tags-list {
  max-height: 300px;
  overflow-y: auto;
  margin-bottom: 16px;
}
.manage-tag-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.manage-tag-info {
  display: flex;
  align-items: center;
  color: var(--el-text-color-primary);
}
.add-tag-form {
  display: flex;
  gap: 8px;
  align-items: center;
}

:global(html.light) .message-meta {
  color: #94a3b8;
}

:global(html.light) .message-role {
  color: #0f172a;
}

:global(html.light) .message-role-sub {
  color: #64748b;
}

:global(html.light) .message-meta-dot {
  background: #cbd5e1;
}

:global(html.light) .queue-status-banner {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(129, 140, 248, 0.05));
  color: #4338ca;
  border-color: rgba(99, 102, 241, 0.18);
}

/* Reasoning / Thinking Section */
.reasoning-container {
  margin: 8px 0 12px 0;
  border: 1px solid rgba(129, 140, 248, 0.2);
  border-radius: 10px;
  overflow: hidden;
  background: rgba(129, 140, 248, 0.04);
  transition: background 0.3s;
}
.reasoning-container.reasoning-done {
  background: rgba(129, 140, 248, 0.02);
  border-color: rgba(129, 140, 248, 0.1);
}
.reasoning-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s;
}
.reasoning-header:hover {
  background: rgba(129, 140, 248, 0.06);
}
.reasoning-icon {
  font-size: 14px;
  line-height: 1;
}
.reasoning-title {
  flex: 1;
  font-size: 13px;
  color: #818cf8;
  font-weight: 500;
}
.reasoning-toggle {
  font-size: 10px;
  color: #818cf8;
  opacity: 0.7;
}
.reasoning-content {
  padding: 0 14px 14px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: #94a3b8;
  max-height: 300px;
  overflow-y: auto;
}
.reasoning-content::-webkit-scrollbar {
  width: 4px;
}
.reasoning-content::-webkit-scrollbar-thumb {
  background: rgba(129, 140, 248, 0.15);
  border-radius: 2px;
}
.reasoning-collapsed {
  padding: 0 14px 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: #64748b;
  opacity: 0.7;
  max-height: 52px;
  overflow: hidden;
  mask-image: linear-gradient(to bottom, black 60%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, black 60%, transparent 100%);
}
:global(html.light) .reasoning-container {
  background: rgba(129, 140, 248, 0.03);
  border-color: rgba(129, 140, 248, 0.12);
}
:global(html.light) .reasoning-title {
  color: #6366f1;
}
:global(html.light) .reasoning-content {
  color: #64748b;
}
:global(html.light) .reasoning-collapsed {
  color: #94a3b8;
}

.attachment-summarize-btn {
  font-size: 11px;
  color: #6366f1;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  padding: 2px 8px;
  border-radius: 10px;
  cursor: pointer;
  margin-left: 6px;
  transition: all 0.2s;
  white-space: nowrap;
  user-select: none;
}
.attachment-summarize-btn:hover {
  background: rgba(99, 102, 241, 0.16);
  border-color: rgba(99, 102, 241, 0.4);
}
.summarize-progress {
  font-size: 12px;
  color: #6366f1;
  padding: 4px 10px;
  margin-top: 4px;
  width: 100%;
  animation: pulse-text 1.5s ease-in-out infinite;
}
@keyframes pulse-text {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
:global(html.light) .attachment-summarize-btn {
  color: #6366f1;
  background: rgba(99, 102, 241, 0.06);
  border-color: rgba(99, 102, 241, 0.15);
}
:global(html.light) .attachment-summarize-btn:hover {
  background: rgba(99, 102, 241, 0.14);
  border-color: rgba(99, 102, 241, 0.35);
}

.knowledge-btn {
  margin-top: 8px;
  width: 100%;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.03);
  color: rgba(255, 255, 255, 0.65);
}
.knowledge-btn:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.85);
}
.knowledge-btn.active {
  background: rgba(129, 140, 248, 0.12);
  border-color: rgba(129, 140, 248, 0.3);
  color: #818cf8;
}

.knowledge-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px 32px;
  overflow-y: auto;
}
.knowledge-header {
  margin-bottom: 20px;
}
.knowledge-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.knowledge-title-row h3 {
  margin: 0;
  font-size: 20px;
  color: var(--el-text-color-primary);
}
.knowledge-stats {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
  padding: 10px 16px;
  background: rgba(255, 255, 255, 0.02);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.05);
}
.knowledge-stat-item {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.knowledge-stat-item strong {
  color: var(--el-text-color-primary);
  margin-left: 4px;
}
.knowledge-search {
  margin-bottom: 4px;
}
.knowledge-table {
  flex: 1;
  max-height: calc(100vh - 320px);
}
.knowledge-file-name {
  font-size: 14px;
  font-weight: 500;
}
.knowledge-pagination {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

:global(html.light) .knowledge-btn {
  border-color: rgba(0, 0, 0, 0.06);
  background: rgba(0, 0, 0, 0.02);
  color: rgba(0, 0, 0, 0.55);
}
:global(html.light) .knowledge-btn:hover {
  background: rgba(0, 0, 0, 0.04);
  color: rgba(0, 0, 0, 0.7);
}
:global(html.light) .knowledge-btn.active {
  background: rgba(99, 102, 241, 0.08);
  border-color: rgba(99, 102, 241, 0.25);
  color: #6366f1;
}
</style>
