import { Button, Card, Col, Empty, List, Row, Space, Tag, Typography } from 'antd'
import type { SessionSummary } from '../../services/api'
import { MessageSquare, FileSearch, Layers, Clock, ChevronRight, Sparkles, ArrowRight } from 'lucide-react'

interface Props {
  username: string
  currentSessionId?: string
  currentReviewId?: string
  sessions: SessionSummary[]
  onSelectSession: (sessionId: string) => void
}

export function DashboardPage({ username, currentSessionId, currentReviewId, sessions, onSelectSession }: Props) {
  const currentSession = sessions.find((item) => item.sessionId === currentSessionId)
  const totalReviews = sessions.reduce((a, s) => a + (s.reviewCount || 0), 0)
  const totalMessages = sessions.reduce((a, s) => a + (s.messageCount || 0), 0)

  const stats = [
    { label: '历史会话', value: sessions.length, icon: MessageSquare, color: '#06b6d4', glow: 'rgba(6,182,212,0.2)' },
    { label: '审查任务', value: totalReviews, icon: FileSearch, color: '#7c3aed', glow: 'rgba(124,58,237,0.2)' },
    { label: '对话消息', value: totalMessages, icon: Layers, color: '#ec4899', glow: 'rgba(236,72,153,0.2)' },
  ]

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: '32px 24px' }}>

      {/* Hero section */}
      <div style={{ marginBottom: 40 }} className="animate-in">
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <Sparkles size={20} color="#06b6d4" style={{ animation: 'auroraPulse 3s ease-in-out infinite' }} />
              <Typography.Text style={{ fontSize: '12px', color: '#06b6d4', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.1em' }}>
                Welcome Back
              </Typography.Text>
            </div>
            <Typography.Title
              level={1}
              style={{
                margin: 0,
                fontFamily: '"Syne", sans-serif',
                fontWeight: 800,
                fontSize: 'clamp(1.8rem, 4vw, 2.8rem)',
                background: 'linear-gradient(135deg, var(--text-primary) 0%, var(--text-secondary) 40%, var(--text-muted) 100%)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                backgroundClip: 'text',
                lineHeight: 1.1,
                letterSpacing: '-0.02em',
              }}
            >
              欢迎回来，{username}
            </Typography.Title>
            <Typography.Paragraph style={{ margin: '10px 0 0', color: 'var(--text-muted)', fontSize: 'var(--text-base)', maxWidth: 520 }}>
              基于多 Agent 协作的智能代码审查助手，支持深度分析、问题追踪与规范化审查。
            </Typography.Paragraph>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            {[
              { label: '代码审查', gradient: 'linear-gradient(135deg, rgba(6,182,212,0.15), rgba(124,58,237,0.15))', border: 'rgba(6,182,212,0.25)', icon: FileSearch, color: '#06b6d4' },
              { label: '开始对话', gradient: 'linear-gradient(135deg, rgba(124,58,237,0.15), rgba(236,72,153,0.15))', border: 'rgba(124,58,237,0.25)', icon: MessageSquare, color: '#7c3aed' },
            ].map((action) => (
              <button key={action.label} style={{
                padding: '10px 18px',
                background: action.gradient,
                border: `1px solid ${action.border}`,
                borderRadius: 'var(--radius-md)',
                color: action.color,
                fontSize: 'var(--text-base)',
                fontWeight: 600,
                fontFamily: '"Syne", sans-serif',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                transition: 'all 0.2s ease',
              }}>
                <action.icon size={16} />
                {action.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Stats row */}
      <Row gutter={16} style={{ marginBottom: 32 }}>
        {stats.map((stat, i) => (
          <Col xs={24} md={8} key={stat.label}>
            <div
              className="animate-in"
              style={{ animationDelay: `${(i + 1) * 0.08}s` }}
            >
              <Card
                style={{
                  background: 'var(--bg-card)',
                  border: `1px solid ${stat.color}22`,
                  borderRadius: 'var(--radius-lg)',
                  overflow: 'hidden',
                  position: 'relative',
                  transition: 'all 0.25s ease',
                }}
                styles={{ body: { padding: '20px 22px' } }}
                onMouseEnter={(e) => {
                  (e.currentTarget as HTMLDivElement).style.borderColor = `${stat.color}55`
                  ;(e.currentTarget as HTMLDivElement).style.boxShadow = `0 8px 40px rgba(0,0,0,0.4), 0 0 20px ${stat.glow}`
                  ;(e.currentTarget as HTMLDivElement).style.transform = 'translateY(-2px)'
                }}
                onMouseLeave={(e) => {
                  ;(e.currentTarget as HTMLDivElement).style.borderColor = `${stat.color}22`
                  ;(e.currentTarget as HTMLDivElement).style.boxShadow = 'none'
                  ;(e.currentTarget as HTMLDivElement).style.transform = 'translateY(0)'
                }}
              >
                {/* Glow accent */}
                <div style={{ position: 'absolute', top: 0, right: 0, width: 100, height: 100, background: `radial-gradient(circle at top right, ${stat.glow}, transparent 70%)`, pointerEvents: 'none' }} />
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
                  <div>
                    <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8 }}>
                      {stat.label}
                    </div>
                    <div style={{ fontSize: '2rem', fontFamily: '"JetBrains Mono", monospace', fontWeight: 700, color: stat.color, lineHeight: 1, textShadow: `0 0 30px ${stat.glow}` }}>
                      {stat.value}
                    </div>
                  </div>
                  <div style={{
                    width: 44, height: 44, borderRadius: 12,
                    background: `${stat.color}12`,
                    border: `1px solid ${stat.color}22`,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}>
                    <stat.icon size={20} color={stat.color} />
                  </div>
                </div>
              </Card>
            </div>
          </Col>
        ))}
      </Row>

      {/* Current session info */}
      {(currentSessionId || currentReviewId) && (
        <div style={{ marginBottom: 32 }} className="animate-in animate-in-4">
          <div style={{
            padding: '16px 20px',
            background: 'linear-gradient(135deg, rgba(6,182,212,0.06) 0%, rgba(124,58,237,0.06) 100%)',
            border: '1px solid rgba(6,182,212,0.15)',
            borderRadius: 'var(--radius-lg)',
            display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#06b6d4', fontSize: '13px', fontWeight: 600, fontFamily: '"Syne", sans-serif' }}>
              <Clock size={15} />
              当前会话
            </div>
            {currentSessionId && <Tag color="blue" style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '11px' }}>{currentSessionId}</Tag>}
            {currentReviewId && <Tag color="purple" style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '11px' }}>{currentReviewId}</Tag>}
            {currentSession && (
              <Typography.Text style={{ fontSize: '12px', color: 'var(--text-muted)', flex: 1 }}>
                {currentSession.latestMessagePreview || '暂无预览'}
              </Typography.Text>
            )}
          </div>
        </div>
      )}

      {/* History sessions */}
      <Card
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Layers size={17} color="#7c3aed" />
            <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: 'var(--text-lg)' }}>历史会话</span>
          </div>
        }
        style={{
          background: 'var(--bg-card)',
          border: '1px solid var(--border-default)',
          borderRadius: 'var(--radius-lg)',
        }}
        styles={{ header: { borderBottom: '1px solid var(--border-default)', padding: '16px 20px' }, body: { padding: sessions.length ? '0' : undefined } }}
        className="animate-in animate-in-5"
      >
        {sessions.length > 0 ? (
          <List
            itemLayout="vertical"
            dataSource={sessions}
            renderItem={(item) => {
              const active = item.sessionId === currentSessionId
              return (
                <List.Item
                  style={{
                    padding: '18px 22px',
                    borderBottom: '1px solid var(--border-default)',
                    transition: 'all 0.15s ease',
                    cursor: 'pointer',
                  }}
                  onMouseEnter={(e) => {
                    ;(e.currentTarget as HTMLDivElement).style.background = 'rgba(148,163,184,0.03)'
                  }}
                  onMouseLeave={(e) => {
                    ;(e.currentTarget as HTMLDivElement).style.background = 'transparent'
                  }}
                  actions={[
                    <Button
                      key="switch"
                      type={active ? 'primary' : 'default'}
                      size="small"
                      onClick={() => onSelectSession(item.sessionId)}
                      icon={active ? undefined : <ArrowRight size={13} />}
                      style={active ? { background: 'linear-gradient(135deg, #06b6d4, #7c3aed)', border: 'none' } : {}}
                    >
                      {active ? '当前会话' : '切换'}
                    </Button>,
                  ]}
                >
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
                    {active && (
                      <div style={{ width: 3, borderRadius: 2, background: 'linear-gradient(180deg, #06b6d4, #7c3aed)', alignSelf: 'stretch', minHeight: 40, flexShrink: 0 }} />
                    )}
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Space wrap style={{ marginBottom: 8 }}>
                        <Tag style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '10.5px', background: active ? 'rgba(6,182,212,0.15)' : 'rgba(148,163,184,0.08)', color: active ? '#67e8f9' : '#64748b', border: 'none' }}>
                          {item.sessionId}
                        </Tag>
                        {item.projectId ? (
                          <Tag style={{ background: 'rgba(234,179,8,0.12)', color: '#fde047', border: 'none', fontSize: '10.5px' }}>
                            项目: {item.projectId}
                          </Tag>
                        ) : null}
                        {item.latestReviewStatus ? (
                          <Tag style={{ background: 'rgba(34,197,94,0.12)', color: '#86efac', border: 'none', fontSize: '10.5px' }}>
                            {item.latestReviewStatus}
                          </Tag>
                        ) : null}
                        {item.language ? (
                          <Tag style={{ background: 'rgba(124,58,237,0.12)', color: '#c4b5fd', border: 'none', fontSize: '10.5px' }}>
                            {item.language}
                          </Tag>
                        ) : null}
                      </Space>
                      <Typography.Paragraph style={{ marginBottom: 8, color: 'var(--text-secondary)', fontSize: 'var(--text-base)' }}>
                        {item.latestMessagePreview || '当前会话还没有可展示的消息摘要'}
                      </Typography.Paragraph>
                      <div style={{ display: 'flex', gap: 16, fontSize: '11px', color: 'var(--text-muted)', fontFamily: '"JetBrains Mono", monospace' }}>
                        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                          <Clock size={11} />
                          {item.lastActivity ? new Date(item.lastActivity).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '未知'}
                        </span>
                        <span>消息 {item.messageCount}</span>
                        <span>审查 {item.reviewCount}</span>
                      </div>
                    </div>
                  </div>
                </List.Item>
              )
            }}
          />
        ) : (
          <div style={{ padding: '48px 0', textAlign: 'center' }}>
            <Empty description={<span style={{ color: 'var(--text-muted)' }}>当前还没有历史会话</span>} />
          </div>
        )}
      </Card>
    </div>
  )
}
