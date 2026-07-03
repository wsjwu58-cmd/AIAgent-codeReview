import { useState } from 'react'
import { Button, Form, Input, Space, Typography } from 'antd'
import { login, register } from '../../services/api'

interface Props {
  onAuthed: (token: string, username: string) => void
}

export function LoginPage({ onAuthed }: Props) {
  const [loading, setLoading] = useState(false)
  const [isRegister, setIsRegister] = useState(false)

  const onLogin = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const data = await login(values.username, values.password)
      onAuthed(data.token, data.user.username)
    } catch {
      setLoading(false)
    }
  }

  const onRegister = async (values: { username: string; password: string; email?: string }) => {
    setLoading(true)
    try {
      const data = await register(values.username, values.password, values.email || '')
      onAuthed(data.token, data.user.username)
    } catch {
      setLoading(false)
    }
  }

  return (
    <div style={styles.wrapper}>
      {/* Ambient orbs */}
      <div style={styles.orbCyan} />
      <div style={styles.orbViolet} />
      <div style={styles.orbPink} />

      {/* Grid overlay */}
      <div style={styles.gridOverlay} />

      <div style={styles.card} className="aurora-card">
        <div style={styles.inner}>

          {/* Logo + Branding */}
          <div style={styles.brand}>
            <div style={styles.logoRing}>
              <div style={styles.logoIcon}>
                <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
                  <path d="M14 2L26 8.5V19.5L14 26L2 19.5V8.5L14 2Z" stroke="url(#lg)" strokeWidth="1.5" fill="none"/>
                  <path d="M14 7L21 10.5V17.5L14 21L7 17.5V10.5L14 7Z" fill="url(#lg2)" opacity="0.6"/>
                  <circle cx="14" cy="14" r="3" fill="url(#lg)"/>
                  <defs>
                    <linearGradient id="lg" x1="2" y1="2" x2="26" y2="26" gradientUnits="userSpaceOnUse">
                      <stop stopColor="#06b6d4"/>
                      <stop offset="0.5" stopColor="#7c3aed"/>
                      <stop offset="1" stopColor="#ec4899"/>
                    </linearGradient>
                    <linearGradient id="lg2" x1="7" y1="7" x2="21" y2="21" gradientUnits="userSpaceOnUse">
                      <stop stopColor="#06b6d4" stopOpacity="0.5"/>
                      <stop offset="1" stopColor="#7c3aed" stopOpacity="0.3"/>
                    </linearGradient>
                  </defs>
                </svg>
              </div>
            </div>
            <Typography.Title
              level={2}
              style={{
                margin: 0,
                fontFamily: '"Syne", sans-serif',
                fontWeight: 800,
                fontSize: '1.75rem',
                background: 'linear-gradient(135deg, #06b6d4 0%, #7c3aed 60%, #ec4899 100%)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                backgroundClip: 'text',
                letterSpacing: '-0.02em',
              }}
            >
              智能代码审查助手
            </Typography.Title>
            <Typography.Text style={{ fontSize: '0.85rem', color: '#64748b', marginTop: 4, display: 'block' }}>
              AI Code Review Assistant
            </Typography.Text>
          </div>

          {/* Tab switcher */}
          <div style={styles.tabBar}>
            <button
              style={{ ...styles.tab, ...(isRegister ? styles.tabInactive : styles.tabActive) }}
              onClick={() => setIsRegister(false)}
            >
              登录
            </button>
            <button
              style={{ ...styles.tab, ...(isRegister ? styles.tabActive : styles.tabInactive) }}
              onClick={() => setIsRegister(true)}
            >
              注册
            </button>
            {!isRegister && <div style={styles.tabIndicator} />}
          </div>

          {/* Form */}
          <Form
            layout="vertical"
            onFinish={isRegister ? onRegister : onLogin}
            style={{ marginBottom: 0 }}
          >
            <Form.Item
              label={<span style={styles.label}>用户名</span>}
              name="username"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input
                prefix={
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#4a5568" strokeWidth="1.5">
                    <circle cx="12" cy="8" r="4"/><path d="M4 20c0-4 3.6-7 8-7s8 3 8 7"/>
                  </svg>
                }
                placeholder="输入用户名"
                style={styles.input}
                size="large"
              />
            </Form.Item>
            <Form.Item
              label={<span style={styles.label}>密码</span>}
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                placeholder="输入密码"
                style={styles.input}
                size="large"
              />
            </Form.Item>
            {isRegister && (
              <Form.Item label={<span style={styles.label}>邮箱（可选）</span>} name="email">
                <Input
                  prefix={
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#4a5568" strokeWidth="1.5">
                      <rect x="2" y="4" width="20" height="16" rx="2"/><path d="m2 7 10 7 10-7"/>
                    </svg>
                  }
                  placeholder="可选"
                  style={styles.input}
                  size="large"
                />
              </Form.Item>
            )}
            <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                block
                style={styles.submitBtn}
                size="large"
              >
                <span style={{ fontWeight: 600, fontSize: '1rem' }}>
                  {isRegister ? '创建账号' : '登录系统'}
                </span>
              </Button>
            </Form.Item>
          </Form>

          <div style={styles.hint}>
            <span style={{ color: '#4a5568' }}>
              {isRegister ? '已有账号？' : '没有账号？'}
            </span>
            <button
              onClick={() => setIsRegister(!isRegister)}
              style={styles.linkBtn}
            >
              {isRegister ? '登录' : '立即注册'}
            </button>
          </div>

          {/* Feature badges */}
          <div style={styles.features}>
            {['多 Agent 协作', '深度代码分析', 'PDF 规范审查'].map((f) => (
              <span key={f} style={styles.featureBadge}>{f}</span>
            ))}
          </div>

        </div>
      </div>
    </div>
  )
}

/* ---- Inline styles ---- */
const styles: Record<string, React.CSSProperties> = {
  wrapper: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: '#060b14',
    padding: '24px',
    position: 'relative',
    overflow: 'hidden',
  },
  orbCyan: {
    position: 'fixed',
    width: 600,
    height: 600,
    borderRadius: '50%',
    background: 'radial-gradient(circle, rgba(6,182,212,0.12) 0%, transparent 70%)',
    top: '-20%',
    left: '-15%',
    pointerEvents: 'none',
    animation: 'auroraPulse 6s ease-in-out infinite',
  },
  orbViolet: {
    position: 'fixed',
    width: 700,
    height: 700,
    borderRadius: '50%',
    background: 'radial-gradient(circle, rgba(124,58,237,0.1) 0%, transparent 70%)',
    bottom: '-20%',
    right: '-15%',
    pointerEvents: 'none',
    animation: 'auroraPulse 8s ease-in-out infinite 2s',
  },
  orbPink: {
    position: 'fixed',
    width: 400,
    height: 400,
    borderRadius: '50%',
    background: 'radial-gradient(circle, rgba(236,72,153,0.06) 0%, transparent 70%)',
    top: '40%',
    right: '30%',
    pointerEvents: 'none',
    animation: 'auroraPulse 10s ease-in-out infinite 4s',
  },
  gridOverlay: {
    position: 'fixed',
    inset: 0,
    backgroundImage: `
      linear-gradient(rgba(148,163,184,0.03) 1px, transparent 1px),
      linear-gradient(90deg, rgba(148,163,184,0.03) 1px, transparent 1px)
    `,
    backgroundSize: '48px 48px',
    pointerEvents: 'none',
  },
  card: {
    width: '100%',
    maxWidth: 440,
    position: 'relative',
    zIndex: 10,
  },
  inner: {
    background: 'rgba(13, 20, 36, 0.95)',
    backdropFilter: 'blur(24px)',
    borderRadius: 'var(--radius-xl)',
    padding: '40px 36px',
    border: '1px solid var(--border-default)',
    boxShadow: '0 24px 80px rgba(0,0,0,0.6), 0 0 0 1px rgba(6,182,212,0.08)',
    animation: 'scaleIn 0.4s cubic-bezier(0.34,1.56,0.64,1) both',
  },
  brand: {
    textAlign: 'center',
    marginBottom: 32,
  },
  logoRing: {
    width: 72,
    height: 72,
    borderRadius: 20,
    background: 'rgba(6,182,212,0.06)',
    border: '1px solid rgba(6,182,212,0.2)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    margin: '0 auto 16px',
    boxShadow: '0 0 40px rgba(6,182,212,0.12), inset 0 0 20px rgba(6,182,212,0.05)',
  },
  logoIcon: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabBar: {
    display: 'flex',
    background: 'rgba(148,163,184,0.05)',
    borderRadius: 10,
    padding: 4,
    marginBottom: 28,
    position: 'relative',
  },
  tab: {
    flex: 1,
    padding: '8px 16px',
    border: 'none',
    background: 'transparent',
    cursor: 'pointer',
    fontSize: '0.9rem',
    fontWeight: 500,
    borderRadius: 7,
    transition: 'all 0.2s ease',
    color: '#64748b',
    fontFamily: '"Noto Sans SC", sans-serif',
    position: 'relative',
    zIndex: 1,
  },
  tabActive: {
    color: '#e2e8f0',
    fontWeight: 600,
  },
  tabInactive: {
    color: '#4a5568',
  },
  tabIndicator: {
    position: 'absolute',
    top: 4,
    left: 4,
    width: 'calc(50% - 4px)',
    height: 'calc(100% - 8px)',
    background: 'linear-gradient(135deg, rgba(6,182,212,0.2) 0%, rgba(124,58,237,0.15) 100%)',
    border: '1px solid rgba(6,182,212,0.25)',
    borderRadius: 7,
    transition: 'transform 0.25s cubic-bezier(0.34,1.56,0.64,1)',
    zIndex: 0,
  },
  label: {
    color: '#94a3b8',
    fontSize: '0.82rem',
    fontWeight: 500,
    letterSpacing: '0.02em',
  },
  input: {
    background: 'var(--bg-input)',
    border: '1px solid var(--border-default)',
    borderRadius: 'var(--radius-md)',
    color: 'var(--text-primary)',
    fontSize: '0.95rem',
    height: 46,
    transition: 'all 0.15s ease',
  },
  submitBtn: {
    height: 50,
    background: 'var(--aurora-gradient)',
    border: 'none',
    borderRadius: 'var(--radius-md)',
    boxShadow: '0 4px 20px rgba(6,182,212,0.3)',
    fontFamily: '"Noto Sans SC", sans-serif',
  },
  hint: {
    textAlign: 'center',
    marginTop: 20,
    fontSize: '0.85rem',
  },
  linkBtn: {
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    color: '#06b6d4',
    fontSize: '0.85rem',
    padding: '0 0 0 6px',
    fontFamily: '"Noto Sans SC", sans-serif',
    textDecoration: 'underline',
    textDecorationColor: 'rgba(6,182,212,0.3)',
  },
  features: {
    display: 'flex',
    justifyContent: 'center',
    gap: 8,
    marginTop: 24,
    flexWrap: 'wrap',
  },
  featureBadge: {
    padding: '3px 10px',
    borderRadius: 99,
    background: 'rgba(148,163,184,0.05)',
    border: '1px solid var(--border-default)',
    fontSize: 'var(--text-xs)',
    color: 'var(--text-muted)',
    fontWeight: 500,
  },
}
