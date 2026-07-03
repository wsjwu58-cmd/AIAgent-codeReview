import { useEffect, useState } from 'react'
import { Button, Card, Input, Popconfirm, Segmented, Space, Table, Tag, Typography, message } from 'antd'
import { Database, Search, Trash2 } from 'lucide-react'
import {
  batchDeleteKnowledgeRecords,
  deleteKnowledgeRecord,
  getKnowledgeRecords,
  type KnowledgeDeleteItem,
  type KnowledgeManageRecord,
  type KnowledgeRecordType,
} from '../../services/api'

const typeOptions = [
  { label: '全部', value: 'ALL' },
  { label: '审查历史', value: 'REVIEW_HISTORY' },
  { label: 'PDF规范', value: 'PDF_NORM' },
  { label: '聊天记录', value: 'CHAT_HISTORY' },
] as const

interface Props {
  currentProjectId?: string
}

export function KnowledgeManagePage({ currentProjectId }: Props) {
  const [type, setType] = useState<'ALL' | KnowledgeRecordType>('ALL')
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [records, setRecords] = useState<KnowledgeManageRecord[]>([])
  const [selectedRows, setSelectedRows] = useState<KnowledgeManageRecord[]>([])
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [total, setTotal] = useState(0)

  const load = async (nextPage = page, nextPageSize = pageSize) => {
    setLoading(true)
    try {
      const data = await getKnowledgeRecords({
        type: type === 'ALL' ? undefined : type,
        projectId: currentProjectId,
        keyword: keyword.trim() || undefined,
        page: nextPage - 1,
        size: nextPageSize,
      })
      setRecords(data.content)
      setTotal(data.totalElements)
      setPage(nextPage)
      setPageSize(nextPageSize)
    } catch {
      message.error('知识库记录加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load(1, pageSize)
  }, [type, currentProjectId])

  const onDeleteOne = async (record: KnowledgeManageRecord) => {
    try {
      await deleteKnowledgeRecord(record.id, record.type)
      message.success('记录已删除')
      await load()
    } catch {
      message.error('删除失败')
    }
  }

  const onDeleteSelected = async () => {
    const payload: KnowledgeDeleteItem[] = selectedRows.map((item) => ({ id: item.id, type: item.type }))
    if (payload.length === 0) {
      return
    }
    try {
      await batchDeleteKnowledgeRecords(payload)
      setSelectedRows([])
      message.success('批量删除完成')
      await load()
    } catch {
      message.error('批量删除失败')
    }
  }

  return (
    <div style={{ maxWidth: 1180, margin: '0 auto', padding: '32px 24px' }}>
      <div className="page-header">
        <div className="page-header__row">
          <div className="page-header__icon">
            <Database size={20} color="#8b5cf6" />
          </div>
          <Typography.Title level={2} className="page-header__title">
            知识库管理
          </Typography.Title>
        </div>
        <Typography.Paragraph className="page-header__desc">
          统一查看并清理审查历史、PDF 规范和聊天记录。
        </Typography.Paragraph>
      </div>

      <Card style={{
        marginBottom: 18,
        background: 'var(--bg-card)',
        border: '1px solid var(--border-default)',
        borderRadius: 'var(--radius-lg)',
      }}>
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Segmented
            options={typeOptions as unknown as { label: string; value: string }[]}
            value={type}
            onChange={(value) => setType(value as 'ALL' | KnowledgeRecordType)}
          />
          <Space.Compact style={{ width: '100%' }}>
            <Input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder="搜索 ID、项目、摘要或文件名"
              onPressEnter={() => void load(1, pageSize)}
              prefix={<Search size={14} color="var(--text-muted)" />}
            />
            <Button type="primary" onClick={() => void load(1, pageSize)}>搜索</Button>
            <Button onClick={() => void load(page, pageSize)}>刷新</Button>
          </Space.Compact>
          <Space wrap>
            <Popconfirm
              title="确认删除选中记录？"
              onConfirm={() => void onDeleteSelected()}
              disabled={selectedRows.length === 0}
            >
              <Button danger disabled={selectedRows.length === 0} icon={<Trash2 size={14} />}>
                删除选中
              </Button>
            </Popconfirm>
            <Typography.Text style={{ color: 'var(--text-secondary)' }}>
              已选择 {selectedRows.length} 项
            </Typography.Text>
          </Space>
        </Space>
      </Card>

      <Card style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border-default)',
        borderRadius: 'var(--radius-lg)',
      }}>
        <Table<KnowledgeManageRecord>
          rowKey={(record) => `${record.type}-${record.id}`}
          loading={loading}
          dataSource={records}
          rowSelection={{
            onChange: (_, rows) => setSelectedRows(rows),
          }}
          pagination={{
            current: page,
            pageSize,
            total,
            onChange: (nextPage, nextPageSize) => void load(nextPage, nextPageSize),
          }}
          columns={[
            {
              title: '类型',
              dataIndex: 'type',
              width: 120,
              render: (value: KnowledgeRecordType) => (
                <Tag style={{
                  margin: 0,
                  background: value === 'REVIEW_HISTORY' ? 'rgba(124,58,237,0.15)'
                    : value === 'PDF_NORM' ? 'rgba(234,179,8,0.15)'
                    : 'rgba(6,182,212,0.15)',
                  color: value === 'REVIEW_HISTORY' ? '#c4b5fd'
                    : value === 'PDF_NORM' ? '#fde047'
                    : '#67e8f9',
                  border: 'none',
                  fontSize: '11px',
                }}>
                  {value}
                </Tag>
              ),
            },
            {
              title: 'ID',
              dataIndex: 'id',
              width: 220,
              ellipsis: true,
              render: (value: string) => (
                <Typography.Text style={{ fontFamily: '"JetBrains Mono", monospace', color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                  {value}
                </Typography.Text>
              ),
            },
            {
              title: '项目/会话',
              render: (_, record) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text style={{ color: 'var(--text-primary)' }}>{record.projectId || '-'}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>{record.sessionId || '-'}</Typography.Text>
                </Space>
              ),
            },
            {
              title: '摘要',
              dataIndex: 'summary',
              ellipsis: true,
            },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (value: number) => (
                <Typography.Text style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                  {new Date(value).toLocaleString()}
                </Typography.Text>
              ),
            },
            {
              title: '操作',
              width: 100,
              render: (_, record) => (
                <Popconfirm title="确认删除该记录？" onConfirm={() => void onDeleteOne(record)}>
                  <Button danger type="link">删除</Button>
                </Popconfirm>
              ),
            },
          ]}
        />
      </Card>
    </div>
  )
}
