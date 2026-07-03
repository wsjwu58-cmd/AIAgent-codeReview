import { useEffect, useMemo, useState } from 'react'
import { Button, ConfigProvider, Layout, Typography, theme } from 'antd'
import { DashboardPage } from './pages/Dashboard'
import { LoginPage } from './pages/Login'
import { ReviewPage } from './pages/Review'
import { ChatPage } from './pages/Chat/Workspace'
import { KnowledgeManagePage } from './pages/KnowledgeManage'
import { PdfUploadPage } from './pages/PdfUpload'
import { listSessions, type SessionSummary } from './services/api'
import { LayoutDashboard, GitPullRequest, MessageSquare, FileUp, ChevronRight, LogOut, Zap, Database } from 'lucide-react'

const SESSION_STORAGE_KEY = 'code-review.selectedSessionId'
const REVIEW_STORAGE_KEY = 'code-review.selectedReviewId'
const SESSION_LIST_STORAGE_KEY = 'code-review.sessionList'

type TabKey = 'dashboard' | 'review' | 'chat' | 'pdf-upload' | 'knowledge-manage'

interface ApiErrorBody { code?: number; message?: string }

export function App() {
  const [token, setToken] = useState<string | null>(localStorage.getItem('token'))
  const [username, setUsername] = useState<string>(localStorage.getItem('username') || '')
  const [activeTab, setActiveTab] = useState<TabKey>('dashboard')
  const [sessionId, setSessionId] = useState<string | undefined>(localStorage.getItem(SESSION_STORAGE_KEY) || undefined)
  const [reviewId, setReviewId] = useState<string | undefined>(localStorage.getItem(REVIEW_STORAGE_KEY) || undefined)
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [sidebarHover, setSidebarHover] = useState<string | null>(null)
  const safeSessions = Array.isArray(sessions) ? sessions : []

  const currentSession = useMemo(
    () => safeSessions.find((item) => item.sessionId === sessionId),
    [safeSessions, sessionId],
  )

  useEffect(() => {
    if (sessionId) localStorage.setItem(SESSION_STORAGE_KEY, sessionId)
    else localStorage.removeItem(SESSION_STORAGE_KEY)
  }, [sessionId])

  useEffect(() => {
    if (reviewId) localStorage.setItem(REVIEW_STORAGE_KEY, reviewId)
    else localStorage.removeItem(REVIEW_STORAGE_KEY)
  }, [reviewId])

  useEffect(() => {
    if (!token) { setSessions([]); return }
    const cached = localStorage.getItem(SESSION_LIST_STORAGE_KEY)
    if (cached) {
      try { setSessions(JSON.parse(cached)) } catch { localStorage.removeItem(SESSION_LIST_STORAGE_KEY) }
    }
    void refreshSessions()
  }, [token])

  const selectSession = (nextSessionId?: string, nextReviewId?: string) => {
    setSessionId(nextSessionId)
    if (nextSessionId) {
      const matched = safeSessions.find((item) => item.sessionId === nextSessionId)
      setReviewId(nextReviewId ?? (matched?.latestReviewId || undefined))
    } else {
      setReviewId(nextReviewId)
    }
  }

  const normalizeSessions = (value: unknown): SessionSummary[] => Array.isArray(value) ? value as SessionSummary[] : []

  const refreshSessions = async (preferredSessionId?: string, preferredReviewId?: string) => {
    try {
      const data = normalizeSessions(await listSessions())
      setSessions(data)
      localStorage.setItem(SESSION_LIST_STORAGE_KEY, JSON.stringify(data))
      const desired = preferredSessionId ?? sessionId ?? data[0]?.sessionId
      const matched = data.find((item) => item.sessionId === desired)
      if (matched) {
        setSessionId(matched.sessionId)
        setReviewId((preferredReviewId ?? matched.latestReviewId) || undefined)
        return
      }
      if (data.length > 0) {
        setSessionId(data[0].sessionId)
        setReviewId(data[0].latestReviewId || undefined)
        return
      }
      setSessionId(undefined)
      setReviewId(undefined)
    } catch {
      const cached = localStorage.getItem(SESSION_LIST_STORAGE_KEY)
      if (cached) {
        try { setSessions(normalizeSessions(JSON.parse(cached))) } catch { localStorage.removeItem(SESSION_LIST_STORAGE_KEY) }
      }
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem(SESSION_STORAGE_KEY)
    localStorage.removeItem(REVIEW_STORAGE_KEY)
    localStorage.removeItem(SESSION_LIST_STORAGE_KEY)
    setSessions([])
    setSessionId(undefined)
    setReviewId(undefined)
    setToken(null)
  }

  const navItems = [
    { key: 'dashboard' as TabKey, label: '工作台', icon: LayoutDashboard, desc: '概览与快速入口' },
    { key: 'review' as TabKey, label: '代码审查', icon: GitPullRequest, desc: 'AI 代码审查分析' },
    { key: 'chat' as TabKey, label: '对话工作台', icon: MessageSquare, desc: '智能问答与深度分析' },
    { key: 'pdf-upload' as TabKey, label: 'PDF 规范', icon: FileUp, desc: '上传管理规范文档' },
  ]

  navItems.push({ key: 'knowledge-manage' as TabKey, label: '知识库管理', icon: Database, desc: '统一查看和清理 RAG 记录' })

  if (!token) {
    return (
      <ConfigProvider
        theme={{
        algorithm: theme.darkAlgorithm,
        token: { colorPrimary: '#06b6d4', borderRadius: 10, fontFamily: '"Noto Sans SC", sans-serif' },
      }}
      >
        <div className="page-shell">
          <LoginPage onAuthed={(newToken, newUsername) => {
            localStorage.setItem('token', newToken)
            localStorage.setItem('username', newUsername)
            setToken(newToken)
            setUsername(newUsername)
          }} />
        </div>
      </ConfigProvider>
    )
  }

  return (
    <ConfigProvider
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#06b6d4',
          borderRadius: 10,
          fontFamily: '"Noto Sans SC", sans-serif',
        },
        components: {
          Layout: { siderBg: '#0d1424', bodyBg: '#0b1120' },
        },
      }}
    >
      <Layout className="page-shell" style={{ display: 'flex', flexDirection: 'row', minHeight: '100vh', padding: 0, background: 'var(--bg-void)' }}>

        {/* ====== SIDEBAR ====== */}
        <Layout.Sider
          width={260}
          style={{
            position: 'fixed',
            height: '100vh',
            left: 0, top: 0, bottom: 0,
            background: 'var(--bg-primary)',
            borderRight: '1px solid var(--border-default)',
            display: 'flex',
            flexDirection: 'column',
            zIndex: 100,
            overflow: 'hidden',
          }}
        >
          {/* Aurora glow top */}
          <div style={{
            position: 'absolute', top: 0, left: 0, right: 0, height: 2,
            background: 'linear-gradient(90deg, transparent, rgba(6,182,212,0.6), rgba(124,58,237,0.6), rgba(236,72,153,0.4), transparent)',
          }} />

          {/* Logo area */}
          <div style={{
            padding: '24px 20px 20px',
            borderBottom: '1px solid var(--border-default)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{
                width: 42, height: 42,
                borderRadius: 12,
                background: 'linear-gradient(135deg, rgba(6,182,212,0.15), rgba(124,58,237,0.15))',
                border: '1px solid rgba(6,182,212,0.25)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                boxShadow: '0 0 24px rgba(6,182,212,0.15)',
                flexShrink: 0,
              }}>
                <Zap size={20} color="#06b6d4" fill="rgba(6,182,212,0.2)" />
              </div>
              <div style={{ minWidth: 0 }}>
                <Typography.Text style={{
                  margin: 0, display: 'block',
                  fontFamily: '"Syne", sans-serif',
                  fontWeight: 700, fontSize: '15px',
                  color: '#e2e8f0',
                  lineHeight: 1.2,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}>
                  智能代码审查
                </Typography.Text>
                <Typography.Text style={{ fontSize: '10.5px', color: '#4a5568', letterSpacing: '0.03em' }}>
                  AI Code Review
                </Typography.Text>
              </div>
            </div>
          </div>

          {/* Navigation */}
          <nav style={{ flex: 1, padding: '16px 12px', overflowY: 'auto' }}>
            <div style={{ marginBottom: 8 }}>
              <div style={{ padding: '0 12px', marginBottom: 6 }}>
                <span style={{ fontSize: '10px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>
                  导航
                </span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                {navItems.map((item) => {
                  const isActive = activeTab === item.key
                  const isHovered = sidebarHover === item.key
                  const Icon = item.icon
                  return (
                    <button
                      key={item.key}
                      onClick={() => setActiveTab(item.key)}
                      onMouseEnter={() => setSidebarHover(item.key)}
                      onMouseLeave={() => setSidebarHover(null)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 12,
                        padding: '10px 12px',
                        borderRadius: 10,
                        border: 'none',
                        cursor: 'pointer',
                        transition: 'all 0.2s cubic-bezier(0.34,1.56,0.64,1)',
                        background: isActive
                          ? 'linear-gradient(135deg, rgba(6,182,212,0.18) 0%, rgba(124,58,237,0.12) 100%)'
                          : isHovered ? 'rgba(148,163,184,0.05)' : 'transparent',
                        color: isActive ? '#67e8f9' : isHovered ? 'var(--text-primary)' : 'var(--text-muted)',
                        width: '100%', textAlign: 'left',
                        transform: isHovered && !isActive ? 'translateX(2px)' : 'none',
                      }}
                    >
                      <div style={{
                        width: 34, height: 34, borderRadius: 8,
                        background: isActive
                          ? 'rgba(6,182,212,0.2)' : isHovered ? 'rgba(148,163,184,0.05)' : 'rgba(148,163,184,0.03)',
                        border: isActive ? '1px solid rgba(6,182,212,0.25)' : '1px solid transparent',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        transition: 'all 0.2s ease',
                        flexShrink: 0,
                      }}>
                        <Icon size={17} color={isActive ? '#06b6d4' : '#64748b'} />
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <span style={{ fontSize: '13.5px', fontWeight: isActive ? 600 : 500, display: 'block', fontFamily: '"Syne", sans-serif' }}>
                          {item.label}
                        </span>
                        <span style={{ fontSize: '10.5px', color: isActive ? 'rgba(103,232,249,0.7)' : 'var(--text-muted)', display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {item.desc}
                        </span>
                      </div>
                      {isActive && (
                        <ChevronRight size={14} color="#06b6d4" style={{ flexShrink: 0 }} />
                      )}
                    </button>
                  )
                })}
              </div>
            </div>

            {/* Stats mini panel */}
            <div className="glass-panel" style={{ margin: '20px 4px 0', padding: '14px' }}>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 10 }}>
                本次会话
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {[
                  { label: '会话', value: safeSessions.length },
                  { label: '审查', value: safeSessions.reduce((a, s) => a + (s.reviewCount || 0), 0) },
                ].map((stat) => (
                  <div key={stat.label} style={{
                    padding: '8px 10px',
                    background: 'rgba(148,163,184,0.04)',
                    borderRadius: 8,
                    border: '1px solid rgba(148,163,184,0.06)',
                  }}>
                    <div style={{ fontSize: '18px', fontFamily: '"JetBrains Mono", monospace', fontWeight: 600, color: '#06b6d4', lineHeight: 1 }}>
                      {stat.value}
                    </div>
                    <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: 2 }}>{stat.label}</div>
                  </div>
                ))}
              </div>
            </div>
          </nav>

          {/* User footer */}
          <div style={{ padding: '16px', borderTop: '1px solid var(--border-default)' }}>
            <div className="glass-panel" style={{
              padding: '10px 12px',
              marginBottom: 10,
              display: 'flex', alignItems: 'center', gap: 10,
            }}>
              <div style={{
                width: 32, height: 32, borderRadius: 8,
                background: 'linear-gradient(135deg, #06b6d4, #7c3aed)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '13px', fontWeight: 700, color: '#fff',
                flexShrink: 0,
              }}>
                {username ? username[0].toUpperCase() : 'U'}
              </div>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ fontSize: '12.5px', fontWeight: 600, color: '#e2e8f0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {username}
                </div>
                <div style={{ fontSize: '10px', color: '#06b6d4' }}>已认证</div>
              </div>
            </div>
            <Button
              block
              icon={<LogOut size={15} />}
              onClick={handleLogout}
              style={{
                background: 'rgba(239,68,68,0.06)',
                border: '1px solid rgba(239,68,68,0.15)',
                color: '#ef4444',
                height: 38,
                borderRadius: 9,
                fontWeight: 500,
                fontSize: '13px',
              }}
            >
              退出登录
            </Button>
          </div>
        </Layout.Sider>

        {/* ====== MAIN CONTENT ====== */}
        <Layout style={{ marginLeft: 260, flex: 1, background: 'var(--bg-void)', minHeight: '100vh' }}>
          <div style={{
            minHeight: '100vh',
            background: 'radial-gradient(ellipse 100% 40% at 50% -10%, rgba(6,182,212,0.03) 0%, transparent 60%)',
          }}>
            <ContentPanel
              activeTab={activeTab}
              username={username}
              sessionId={sessionId}
              reviewId={reviewId}
              safeSessions={safeSessions}
              currentSession={currentSession}
              onSelectSession={selectSession}
              refreshSessions={refreshSessions}
            />
          </div>
        </Layout>
      </Layout>
    </ConfigProvider>
  )
}

function ContentPanel({ activeTab, username, sessionId, reviewId, safeSessions, currentSession, onSelectSession, refreshSessions }: {
  activeTab: TabKey
  username: string
  sessionId?: string
  reviewId?: string
  safeSessions: SessionSummary[]
  currentSession?: SessionSummary
  onSelectSession: (s?: string, r?: string) => void
  refreshSessions: (s?: string, r?: string) => Promise<void>
}) {
  switch (activeTab) {
    case 'dashboard':
      return (
        <DashboardPage
          username={username}
          currentSessionId={sessionId}
          currentReviewId={reviewId}
          sessions={safeSessions}
          onSelectSession={(id) => onSelectSession(id)}
        />
      )
    case 'review':
      return (
        <ReviewPage
          currentSessionId={sessionId}
          currentReviewId={reviewId}
          onCreated={(s, r) => { onSelectSession(s, r); void refreshSessions(s, r) }}
        />
      )
    case 'chat':
      return (
        <ChatPage
          sessionId={sessionId}
          sessions={safeSessions}
          onSessionChange={(id) => onSelectSession(id)}
          refreshSessions={refreshSessions}
          currentProjectId={currentSession?.projectId}
        />
      )
    case 'pdf-upload':
      return <PdfUploadPage />
    case 'knowledge-manage':
      return <KnowledgeManagePage currentProjectId={currentSession?.projectId} />
    default:
      return null
  }
}
