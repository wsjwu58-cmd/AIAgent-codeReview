import { useState } from 'react'
import { Button, Empty, Input, Segmented, Space, Tag, Typography } from 'antd'
import { ChatMessage } from '../../components/ChatMessage/MessageCard'
import { FileUpload } from '../../components/FileUpload'
import { FolderSelector } from '../../components/FolderSelector'
import { LocalCodeTerminal } from '../../components/LocalCodeTerminal'
import { SessionList } from '../../components/SessionList/Panel'
import { useConversationalChat as useConversationalChatClean } from '../../hooks/useConversationalChatClean'
import { useLocalCodeStream } from '../../hooks/useLocalCodeStream'
import type { SessionSummary } from '../../services/api'
import { FileCode2, MessageSquare, Sparkles, Zap } from 'lucide-react'

interface Props {
  sessionId?: string
  sessions: SessionSummary[]
  currentProjectId?: string
  onSessionChange: (sessionId?: string) => void
  refreshSessions: (preferredSessionId?: string) => Promise<void>
}

export function ChatPage({ sessionId, sessions, currentProjectId, onSessionChange, refreshSessions }: Props) {
  const [activeMode, setActiveMode] = useState<'chat' | 'local-code'>('chat')
  const [folderPath, setFolderPath] = useState('E:/AIAgent')
  const [fileFilters, setFileFilters] = useState('*.java, *.xml, *.yml, *.yaml')
  const {
    draft, setDraft, messages, norms, loadingHistory,
    sending, uploading, sendSimpleMessage, sendDeepAnalysis, uploadNormFile,
  } = useConversationalChatClean({ sessionId, currentProjectId, onSessionChange, refreshSessions })
  const localCodeStream = useLocalCodeStream()

  const currentSession = sessions.find((s) => s.sessionId === sessionId)
  const projectName = currentProjectId || currentSession?.projectId || sessionId?.slice(0, 12) || '未命名项目'
  const language = currentSession?.language || '未知语言'
  const isAnalyzing = sending || localCodeStream.status === 'running'

  const startLocalCodeAnalysis = async (autoWrite: boolean) => {
    if (!folderPath.trim()) {
      return
    }
    try {
      await localCodeStream.start({
        folderPath: folderPath.trim(),
        fileFilters: fileFilters.split(',').map((item) => item.trim()).filter(Boolean),
        autoWrite,
      })
    } catch {
      // handled by hook
    }
  }

  const modeOptions = [
    { value: 'chat', label: '对话工作台' },
    { value: 'local-code', label: '本地代码审查' },
  ]

  const renderHeader = (mode: 'chat' | 'local-code') => (
    <div className="project-bar" style={{ marginBottom: 16 }}>
      <div style={{
        width: 36, height: 36, borderRadius: 10,
        background: mode === 'chat'
          ? 'linear-gradient(135deg, rgba(124,58,237,0.2), rgba(6,182,212,0.15))'
          : 'linear-gradient(135deg, rgba(6,182,212,0.2), rgba(124,58,237,0.15))',
        border: `1px solid ${mode === 'chat' ? 'rgba(124,58,237,0.2)' : 'rgba(6,182,212,0.2)'}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        {mode === 'chat' ? (
          <MessageSquare size={18} color="#8b5cf6" />
        ) : (
          <FileCode2 size={18} color="#06b6d4" />
        )}
      </div>
      <div>
        <div className="project-bar__name">
          {mode === 'chat' ? '对话工作台' : '本地代码审查'}
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: 2 }}>
          {mode === 'chat' ? (sessionId ? `会话 ${sessionId.slice(0, 16)}` : '新建会话') : '选择白名单目录并发起流式分析'}
        </div>
      </div>
      <div className="project-bar__meta">
        <Tag style={{ margin: 0, background: 'rgba(6,182,212,0.12)', color: '#67e8f9', border: 'none', fontSize: '11px' }}>
          {language}
        </Tag>
      </div>
      <div className="project-bar__status">
        <span className={isAnalyzing ? 'project-bar__dot' : 'project-bar__dot project-bar__dot--idle'} />
        {isAnalyzing ? '分析中' : '就绪'}
      </div>
      <Segmented
        size="small"
        value={activeMode}
        options={modeOptions}
        onChange={(value) => setActiveMode(value as 'chat' | 'local-code')}
        style={{ flexShrink: 0 }}
      />
    </div>
  )

  const renderQuickTags = () => (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
      {[
        { label: '快速问答', icon: MessageSquare, color: '#06b6d4' },
        { label: '深度分析', icon: Sparkles, color: '#7c3aed' },
        { label: 'PDF 规范', icon: Zap, color: '#ec4899' },
      ].map((tag) => (
        <Tag key={tag.label} style={{
          display: 'flex', alignItems: 'center', gap: 5,
          background: `${tag.color}0a`,
          border: `1px solid ${tag.color}22`,
          color: tag.color,
          fontSize: '11px',
          fontWeight: 500,
          borderRadius: 99,
          margin: 0,
        }}>
          <tag.icon size={11} />
          {tag.label}
        </Tag>
      ))}
    </div>
  )

  if (activeMode === 'local-code') {
    return (
      <div style={{ display: 'grid', gridTemplateColumns: '280px minmax(0, 1fr)', gap: 0, height: '100vh' }}>
        <aside style={{
          borderRight: '1px solid var(--border-default)',
          background: 'var(--bg-primary)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}>
          <SessionList
            sessions={sessions}
            currentSessionId={sessionId}
            norms={norms}
            onSelectSession={onSessionChange}
            onCreateSession={() => onSessionChange(undefined)}
          />
        </aside>

        <section style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg-void)' }}>
          <div style={{
            padding: '22px 28px 18px',
            borderBottom: '1px solid var(--border-default)',
            background: 'linear-gradient(180deg, rgba(6,182,212,0.03) 0%, transparent 100%)',
            flexShrink: 0,
          }}>
            {renderHeader('local-code')}
          </div>

          <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px', display: 'grid', gap: 18 }}>
            <FolderSelector
              folderPath={folderPath}
              fileFilters={fileFilters}
              disabled={localCodeStream.status === 'running'}
              onFolderPathChange={setFolderPath}
              onFileFiltersChange={setFileFilters}
              onStart={() => void startLocalCodeAnalysis(false)}
              onAutoFix={() => void startLocalCodeAnalysis(true)}
            />
            <LocalCodeTerminal
              lines={localCodeStream.lines}
              status={localCodeStream.status}
              summary={localCodeStream.summary}
              modifiedFiles={localCodeStream.modifiedFiles}
            />
          </div>
        </section>
      </div>
    )
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '280px minmax(0, 1fr)', gap: 0, height: '100vh' }}>
      <aside style={{
        borderRight: '1px solid var(--border-default)',
        background: 'var(--bg-primary)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
      }}>
        <SessionList
          sessions={sessions}
          currentSessionId={sessionId}
          norms={norms}
          onSelectSession={onSessionChange}
          onCreateSession={() => onSessionChange(undefined)}
        />
      </aside>

      <section style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg-void)' }}>
        <div style={{
          padding: '22px 28px 0',
          borderBottom: '1px solid var(--border-default)',
          background: 'linear-gradient(180deg, rgba(6,182,212,0.03) 0%, transparent 100%)',
          flexShrink: 0,
        }}>
          {renderHeader('chat')}
          {renderQuickTags()}
        </div>

        <div style={{
          flex: 1,
          overflowY: 'auto',
          padding: '20px 24px',
          scrollBehavior: 'smooth',
        }}>
          {messages.length ? (
            messages.map((item) => <ChatMessage key={item.id} message={item} />)
          ) : (
            <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Empty
                description={
                  <Space direction="vertical" size={4} style={{ textAlign: 'center' }}>
                    <Typography.Text style={{ color: 'var(--text-muted)', fontSize: '14px' }}>
                      {loadingHistory ? '正在加载会话消息...' : '发送第一条消息，开启智能分析'}
                    </Typography.Text>
                    <Typography.Text style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
                      {loadingHistory ? 'Loading...' : 'Start your first analysis'}
                    </Typography.Text>
                  </Space>
                }
              >
                <div style={{ marginTop: 16 }}>
                  <svg width="80" height="80" viewBox="0 0 80 80" fill="none" style={{ margin: '0 auto', display: 'block', opacity: 0.15 }}>
                    <circle cx="40" cy="40" r="36" stroke="url(#msg)" strokeWidth="1.5" strokeDasharray="5 5"/>
                    <path d="M28 35c0-6.627 5.373-12 12-12s12 5.373 12 12c0 5.523-3.716 10.18-8.727 11.616L40 50l-3.273-3.384C31.716 45.18 28 40.523 28 35z" stroke="url(#msg)" strokeWidth="1.5" fill="none"/>
                    <circle cx="35" cy="35" r="2" fill="#06b6d4"/>
                    <circle cx="40" cy="35" r="2" fill="#7c3aed"/>
                    <circle cx="45" cy="35" r="2" fill="#ec4899"/>
                    <defs><linearGradient id="msg" x1="0" y1="0" x2="80" y2="80"><stop stopColor="#06b6d4"/><stop offset="1" stopColor="#7c3aed"/></linearGradient></defs>
                  </svg>
                </div>
              </Empty>
            </div>
          )}
        </div>

        <div className="chat-composer">
          <div className="chat-composer__actions">
            <Button
              type="primary"
              size="small"
              loading={sending}
              onClick={() => void sendSimpleMessage()}
              style={{ fontSize: '12.5px', height: 30 }}
            >
              <MessageSquare size={13} /> 快速问答
            </Button>
            <Button
              size="small"
              loading={sending}
              onClick={() => void sendDeepAnalysis()}
              style={{ background: 'rgba(124,58,237,0.12)', border: '1px solid rgba(124,58,237,0.25)', color: '#c4b5fd', fontSize: '12.5px', height: 30 }}
            >
              <Sparkles size={13} /> 深度分析
            </Button>
            <div style={{ flex: 1 }} />
            <FileUpload uploading={uploading} onUpload={uploadNormFile} />
          </div>

          <Input.TextArea
            value={draft}
            autoSize={{ minRows: 2, maxRows: 8 }}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="输入你的问题、代码片段或希望重点分析的方向..."
            style={{
              fontSize: '14px',
              lineHeight: 1.6,
              fontFamily: '"Noto Sans SC", sans-serif',
              resize: 'none',
            }}
          />
        </div>
      </section>
    </div>
  )
}
