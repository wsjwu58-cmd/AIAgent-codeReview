import { useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, Collapse, Form, Input, Radio, Row, Space, Tag, Typography, message } from 'antd'
import { useAgentStream } from '../../hooks/useAgentStream'
import { cancelReview, downloadReviewPdf, getReview, submitReview, type ReviewTaskDetail, type ReviewType } from '../../services/api'
import { CodeViewer } from '../../components/CodeViewer'
import { DiffViewer } from '../../components/DiffViewer'
import { ReviewReport } from '../../components/ReviewReport'
import { AgentMessage } from '../../components/AgentMessage'
import { FileSearch, Zap, FileCode, ClipboardList, Loader, X, RefreshCw } from 'lucide-react'

interface Props {
  onCreated: (sessionId: string, reviewId: string) => void
  currentReviewId?: string
  currentSessionId?: string
}

export function ReviewPage({ onCreated, currentReviewId, currentSessionId }: Props) {
  const [reviewId, setReviewId] = useState(currentReviewId)
  const [sessionId, setSessionId] = useState(currentSessionId)
  const [detail, setDetail] = useState<ReviewTaskDetail>()
  const [submitting, setSubmitting] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [activeKeys, setActiveKeys] = useState<string[]>([])
  const [form] = Form.useForm()
  const reviewType = Form.useWatch('type', form) as ReviewType | undefined

  const stream = useAgentStream(sessionId)
  const agentOutputs = useMemo(() => {
    const mergedOutputs = {
      ...(detail?.agentOutputs ?? {}),
      ...stream.outputs,
    }
    return Object.entries(mergedOutputs).filter(([, content]) => Boolean(content?.trim()))
  }, [detail?.agentOutputs, stream.outputs])
  const refactoredCode = detail?.refactoredCode
    || detail?.agentOutputs?.['refactor-agent']
    || detail?.agentOutputs?.RefactorAgent
    || stream.outputs['refactor-agent']
    || stream.outputs.RefactorAgent
    || ''
  const hasResults = detail?.report || agentOutputs.length > 0 || refactoredCode || detail?.codeContent

  const loadReviewDetail = async (targetReviewId: string, options?: { silent?: boolean; suppressError?: boolean }) => {
    const silent = options?.silent ?? false
    const suppressError = options?.suppressError ?? false
    try {
      const data = await getReview(targetReviewId)
      setDetail(data)
      if (!silent) message.success('已刷新审查报告')
      return data
    } catch {
      if (!suppressError) message.error('获取审查报告失败')
      throw new Error()
    }
  }

  useEffect(() => { setReviewId(currentReviewId) }, [currentReviewId])
  useEffect(() => { setSessionId(currentSessionId) }, [currentSessionId])

  useEffect(() => {
    if (!currentReviewId) return
    void loadReviewDetail(currentReviewId, { silent: true, suppressError: true })
  }, [currentReviewId])

  useEffect(() => {
    if (!reviewId || stream.done) return
    const timer = window.setInterval(() => {
      void loadReviewDetail(reviewId, { silent: true, suppressError: true })
    }, 2500)
    return () => window.clearInterval(timer)
  }, [reviewId, stream.done])

  useEffect(() => {
    if (!stream.done || !reviewId) return
    void loadReviewDetail(reviewId, { silent: true, suppressError: true })
  }, [stream.done, reviewId])

  const onSubmit = async (values: { type: ReviewType; codeContent?: string; repoUrl?: string; branch?: string; projectId?: string; language?: string }) => {
    setSubmitting(true)
    setDetail(undefined)
    setSessionId(undefined)
    setReviewId(undefined)
    setActiveKeys(['progress', 'agents', 'results', 'report'])
    try {
      const summary = await submitReview({
        type: values.type,
        codeContent: values.codeContent,
        repoUrl: values.repoUrl,
        branch: values.branch,
        projectId: values.projectId,
        language: values.language || 'java',
      })
      setSessionId(summary.sessionId)
      setReviewId(summary.reviewId)
      onCreated(summary.sessionId, summary.reviewId)
      await loadReviewDetail(summary.reviewId, { silent: true, suppressError: true })
      message.success('已提交审查任务，正在生成结果')
    } catch (err) {
      message.error(extractApiErrorMessage(err, '提交失败，请检查输入内容'))
    } finally {
      setSubmitting(false)
    }
  }

  const onCancel = async () => {
    if (!sessionId) return
    try {
      await cancelReview(sessionId)
      message.success('已发送取消指令')
    } catch {
      message.error('取消失败')
    }
  }

  const onDownloadPdf = async () => {
    if (!reviewId) return
    try {
      setDownloading(true)
      const blob = await downloadReviewPdf(reviewId)
      const url = window.URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `review_${reviewId}.pdf`
      anchor.click()
      window.URL.revokeObjectURL(url)
    } catch {
      message.error('PDF 导出失败')
    } finally {
      setDownloading(false)
    }
  }

  const collapseItems = [
    {
      key: 'progress',
      label: (
        <Space size={10}>
          <Loader size={15} color={stream.done ? '#22c55e' : stream.connected ? '#06b6d4' : '#64748b'} />
          <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: '13.5px' }}>执行进度</span>
          <Tag style={{
            background: stream.done ? 'rgba(34,197,94,0.15)' : stream.connected ? 'rgba(6,182,212,0.15)' : 'rgba(148,163,184,0.08)',
            color: stream.done ? '#86efac' : stream.connected ? '#67e8f9' : '#64748b',
            border: 'none',
          }}>
            {stream.done ? '已完成' : stream.connected ? '执行中' : '等待中'}
          </Tag>
          {stream.progress && (
            <Tag style={{ background: 'rgba(234,179,8,0.12)', color: '#fde047', border: 'none' }}>
              {stream.progress.nodeName}
            </Tag>
          )}
        </Space>
      ),
      children: (
        <Row gutter={16}>
          <Col xs={24} lg={8}>
            <AgentMessage name="实时事件流" content={stream.events.join('\n')} />
          </Col>
          <Col xs={24} lg={16}>
            <Card size="small" title={<span style={{ fontSize: '13px', fontFamily: '"Syne", sans-serif' }}>任务状态</span>} styles={{ header: { borderBottom: '1px solid rgba(148,163,184,0.05)' }, body: { padding: '14px 16px' } }}>
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                {[
                  { label: '会话 ID', value: sessionId || '未创建' },
                  { label: '任务 ID', value: reviewId || '未创建' },
                  { label: '当前节点', value: stream.progress?.nodeName || '等待执行' },
                  { label: '完成状态', value: stream.done ? '已完成' : '执行中' },
                ].map((item) => (
                  <div key={item.label} style={{ display: 'flex', gap: 8, fontSize: '13px' }}>
                    <span style={{ color: '#4a5568', minWidth: 70 }}>{item.label}:</span>
                    <span style={{ fontFamily: '"JetBrains Mono", monospace', color: '#94a3b8', fontSize: '12px' }}>{item.value}</span>
                  </div>
                ))}
                {stream.error && (
                  <div style={{ color: '#ef4444', fontSize: '13px', padding: '6px 10px', background: 'rgba(239,68,68,0.08)', borderRadius: 6, border: '1px solid rgba(239,68,68,0.15)' }}>
                    错误: {stream.error}
                  </div>
                )}
              </Space>
            </Card>
          </Col>
        </Row>
      ),
    },
    {
      key: 'agents',
      label: (
        <Space size={10}>
          <Zap size={15} color="#7c3aed" />
          <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: '13.5px' }}>Agent 输出</span>
          {agentOutputs.length > 0 && (
            <Tag style={{ background: 'rgba(124,58,237,0.15)', color: '#c4b5fd', border: 'none' }}>
              {agentOutputs.length} 个 Agent
            </Tag>
          )}
        </Space>
      ),
      children: agentOutputs.length > 0 ? (
        <Row gutter={16}>
          {agentOutputs.map(([agentId, content]) => (
            <Col key={agentId} xs={24} lg={12}>
              <AgentMessage name={agentId} content={content} />
            </Col>
          ))}
        </Row>
      ) : (
        <Typography.Text type="secondary">暂无 Agent 输出</Typography.Text>
      ),
    },
    {
      key: 'results',
      label: (
        <Space size={10}>
          <FileCode size={15} color="#06b6d4" />
          <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: '13.5px' }}>代码结果</span>
        </Space>
      ),
      children: (
        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <CodeViewer code={refactoredCode} title="重构结果" />
          </Col>
          <Col xs={24} lg={12}>
            <DiffViewer diff={detail?.codeContent} />
          </Col>
        </Row>
      ),
    },
    {
      key: 'report',
      label: (
        <Space size={10}>
          <ClipboardList size={15} color={detail?.report?.criticalCount ? '#ef4444' : detail?.report?.highCount ? '#f97316' : '#22c55e'} />
          <span style={{ fontFamily: '"Syne", sans-serif', fontWeight: 600, fontSize: '13.5px' }}>审查报告</span>
          {detail?.report && (
            <Tag style={{
              background: detail.report.criticalCount > 0 ? 'rgba(239,68,68,0.15)' : detail.report.highCount > 0 ? 'rgba(249,115,22,0.15)' : 'rgba(34,197,94,0.15)',
              color: detail.report.criticalCount > 0 ? '#fca5a5' : detail.report.highCount > 0 ? '#fdba74' : '#86efac',
              border: 'none',
            }}>
              {detail.report.totalIssues} 个问题
            </Tag>
          )}
        </Space>
      ),
      children: <ReviewReport detail={detail} />,
    },
  ]

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto', padding: '32px 24px' }}>

      {/* Header */}
      <div className="page-header">
        <div className="page-header__row">
          <div className="page-header__icon">
            <FileSearch size={20} color="#06b6d4" />
          </div>
          <Typography.Title level={2} className="page-header__title">
            代码审查
          </Typography.Title>
        </div>
        <Typography.Paragraph className="page-header__desc">
          提交代码进行 AI 多 Agent 协作审查，获得问题分析、重构建议与完整报告。
        </Typography.Paragraph>
      </div>

      {/* Submit card */}
      <Card
        style={{
          background: 'var(--bg-card)',
          border: '1px solid var(--border-default)',
          borderRadius: 'var(--radius-lg)',
          marginBottom: 20,
        }}
        styles={{ header: { borderBottom: '1px solid var(--border-default)', padding: '16px 22px' }, body: { padding: '22px' } }}
        className="animate-in animate-in-1"
      >
        <Form form={form} layout="vertical" onFinish={onSubmit} initialValues={{ type: 'PASTE_CODE', language: 'java' }}>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item name="type" label={<span style={labelStyle}>审查类型</span>}>
                <Radio.Group style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {[
                    { value: 'PASTE_CODE', label: '代码粘贴审查' },
                    { value: 'GIT_DIFF', label: '仓库拉取审查' },
                  ].map((opt) => (
                    <Radio.Button
                      key={opt.value}
                      value={opt.value}
                      style={{
                        textAlign: 'center',
                        height: 40,
                        lineHeight: '38px',
                        background: 'rgba(148,163,184,0.04)',
                        border: '1px solid rgba(148,163,184,0.1)',
                        borderRadius: 9,
                        fontFamily: '"Noto Sans SC", sans-serif',
                        fontSize: '13px',
                      }}
                    >
                      {opt.label}
                    </Radio.Button>
                  ))}
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col xs={24} md={16}>
              <Row gutter={[12, 0]}>
                <Col span={12}>
                  <Form.Item name="projectId" label={<span style={labelStyle}>项目 ID</span>}>
                    <Input placeholder="如 project-001" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="language" label={<span style={labelStyle}>语言</span>}>
                    <Input placeholder="java / python / ts" />
                  </Form.Item>
                </Col>
                {reviewType === 'GIT_DIFF' ? (
                  <Col span={24}>
                    <Row gutter={12}>
                      <Col xs={24} md={16}>
                        <Form.Item name="repoUrl" label={<span style={labelStyle}>仓库地址</span>}>
                          <Input placeholder="输入 Git 仓库地址" prefix={<span style={{ color: 'var(--text-muted)', fontSize: 12 }}>git://</span>} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} md={8}>
                        <Form.Item name="branch" label={<span style={labelStyle}>分支</span>}>
                          <Input placeholder="如 main / master / dev" />
                        </Form.Item>
                      </Col>
                    </Row>
                  </Col>
                ) : (
                  <Col span={24}>
                    <Form.Item name="codeContent" label={<span style={labelStyle}>代码内容</span>}>
                      <Input.TextArea
                        rows={5}
                        placeholder="粘贴需要审查的代码"
                        style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '12.5px' }}
                      />
                    </Form.Item>
                  </Col>
                )}
              </Row>
            </Col>
          </Row>

          {/* Actions */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', paddingTop: 4 }}>
            <Button type="primary" htmlType="submit" loading={submitting} icon={<Zap size={15} />} size="large">
              提交审查
            </Button>
            <Button disabled={!reviewId} onClick={() => reviewId && void loadReviewDetail(reviewId)} icon={<RefreshCw size={14} />} size="middle">
              刷新结果
            </Button>
            <Button disabled={!reviewId} loading={downloading} onClick={() => void onDownloadPdf()} size="middle">
              导出 PDF
            </Button>
            <Button danger disabled={!sessionId} onClick={onCancel} icon={<X size={14} />} size="middle">
              取消任务
            </Button>
            <div style={{ flex: 1 }} />
            {sessionId && (
              <Tag style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '11px', background: 'rgba(6,182,212,0.1)', color: '#67e8f9', border: 'none' }}>
                会话: {sessionId}
              </Tag>
            )}
            {reviewId && (
              <Tag style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: '11px', background: 'rgba(124,58,237,0.1)', color: '#c4b5fd', border: 'none' }}>
                任务: {reviewId}
              </Tag>
            )}
          </div>
        </Form>
      </Card>

      {/* Results */}
      {hasResults && (
        <Collapse
          activeKey={activeKeys}
          onChange={(keys) => setActiveKeys(keys as string[])}
          items={collapseItems}
          defaultActiveKey={['progress', 'agents', 'results', 'report']}
          collapsible="icon"
          style={{
            background: 'var(--bg-card)',
            border: '1px solid var(--border-default)',
            borderRadius: 'var(--radius-lg)',
          }}
          className="animate-in animate-in-2"
        />
      )}

      {!hasResults && (
        <Card
          style={{
            background: 'var(--bg-card)',
            border: '1px solid var(--border-default)',
            borderRadius: 'var(--radius-lg)',
            textAlign: 'center',
            padding: '60px 0',
          }}
          className="animate-in animate-in-2"
        >
          <div style={{ marginBottom: 16 }}>
            <svg width="56" height="56" viewBox="0 0 56 56" fill="none" style={{ margin: '0 auto', display: 'block', opacity: 0.3 }}>
              <circle cx="28" cy="28" r="26" stroke="url(#rq)" strokeWidth="1.5" strokeDasharray="4 4"/>
              <path d="M20 22h16M20 28h10M20 34h12" stroke="url(#rq)" strokeWidth="1.5" strokeLinecap="round"/>
              <defs><linearGradient id="rq" x1="0" y1="0" x2="56" y2="56"><stop stopColor="#06b6d4"/><stop offset="1" stopColor="#7c3aed"/></linearGradient></defs>
            </svg>
          </div>
          <Typography.Paragraph style={{ color: 'var(--text-muted)', fontSize: 'var(--text-base)' }}>
            提交代码审查后，结果将在此展示
          </Typography.Paragraph>
          <Typography.Text style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)', display: 'block', marginTop: 6 }}>
            Submit code for review to see results here
          </Typography.Text>
        </Card>
      )}
    </div>
  )
}

function extractApiErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const response = (err as { response?: { data?: { message?: string } } }).response
    if (response?.data?.message) return response.data.message
  }
  return fallback
}

const labelStyle: React.CSSProperties = {
  color: 'var(--text-secondary)',
  fontSize: '12px',
  fontWeight: 500,
  letterSpacing: '0.02em',
}
