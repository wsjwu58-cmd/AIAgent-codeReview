import { Button, Space, Tag, Typography } from 'antd'
import type { ThinkingStepItem } from '../../hooks/useReActStream'
import { Brain, Wrench, ChevronDown, ChevronUp } from 'lucide-react'

interface Props {
  steps: ThinkingStepItem[]
  status: 'thinking' | 'done' | 'error'
  collapsed: boolean
  onToggle: () => void
}

export function ThinkingPanel({ steps, status, collapsed, onToggle }: Props) {
  if (!steps.length && status === 'done') {
    return null
  }

  if (collapsed) {
    return (
      <Button
        type="link"
        size="small"
        onClick={onToggle}
        icon={<ChevronDown size={13} />}
        style={{
          fontSize: 'var(--text-sm)',
          color: '#a78bfa',
          padding: '2px 6px',
          height: 'auto',
          display: 'flex',
          alignItems: 'center',
          gap: 4,
        }}
      >
        查看推理过程 ({steps.length} 步)
      </Button>
    )
  }

  return (
    <div
      className="thinking-panel"
      style={{
        background: 'linear-gradient(135deg, rgba(124,58,237,0.08) 0%, rgba(6,182,212,0.05) 100%)',
        border: '1px solid rgba(124, 58, 237, 0.2)',
        borderRadius: 'var(--radius-md)',
        padding: 14,
        marginBottom: 10,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <Space size={8}>
          <Brain size={14} color="#7c3aed" />
          <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: 'var(--text-sm)', color: '#a78bfa', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            推理过程
          </span>
          <Tag style={{
            background: status === 'thinking' ? 'rgba(234,179,8,0.15)' : status === 'error' ? 'rgba(239,68,68,0.15)' : 'rgba(34,197,94,0.15)',
            color: status === 'thinking' ? '#fde047' : status === 'error' ? '#fca5a5' : '#86efac',
            border: 'none',
            fontSize: '10px',
            padding: '0 7px',
          }}>
            {status === 'thinking' ? '分析中...' : status === 'error' ? '失败' : '完成'}
          </Tag>
          {status === 'thinking' && (
            <div style={{ width: 14, height: 14, border: '2px solid rgba(234,179,8,0.3)', borderTopColor: '#eab308', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />
          )}
        </Space>
        <Button
          type="link"
          size="small"
          onClick={onToggle}
          icon={<ChevronUp size={13} />}
          style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', padding: '2px 4px', height: 'auto' }}
        >
          收起
        </Button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {steps.map((step, idx) => {
          const isTool = step.type === 'TOOL_CALL'
          return (
            <div
              key={`${step.step}-${step.agentId}-${step.type}`}
              style={{
                borderLeft: `2px solid ${isTool ? 'rgba(6,182,212,0.5)' : 'rgba(124,58,237,0.4)'}`,
                paddingLeft: 14,
                animation: `fadeSlideLeft 0.3s cubic-bezier(0.34,1.56,0.64,1) both`,
                animationDelay: `${idx * 0.03}s`,
              }}
            >
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 5 }}>
                {isTool ? (
                  <Wrench size={11} color="#06b6d4" />
                ) : (
                  <Brain size={11} color="#7c3aed" />
                )}
                <Tag style={{
                  fontSize: '9.5px',
                  padding: '0 6px',
                  height: 15,
                  lineHeight: '13px',
                  background: isTool ? 'rgba(6,182,212,0.12)' : 'rgba(124,58,237,0.12)',
                  color: isTool ? '#67e8f9' : '#a78bfa',
                  border: 'none',
                }}>
                  {step.agentName || step.agentId}
                </Tag>
                <span style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                  #{step.step}
                </span>
                {isTool && step.tool && (
                  <Tag style={{ fontSize: 'var(--text-xs)', padding: '0 5px', height: 14, background: 'rgba(6,182,212,0.08)', color: '#4dd0e1', border: 'none' }}>
                    {step.tool}
                  </Tag>
                )}
              </div>
              {isTool ? (
                <div>
                  {step.output && (
                    <Typography.Paragraph style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontFamily: '"JetBrains Mono", monospace' }}>
                      {step.output}
                    </Typography.Paragraph>
                  )}
                </div>
              ) : (
                <Typography.Paragraph style={{ fontSize: 'var(--text-base)', color: 'var(--text-secondary)', margin: 0, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {step.content}
                </Typography.Paragraph>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
