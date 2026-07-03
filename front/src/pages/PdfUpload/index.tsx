import { useState } from 'react'
import { Typography, Card, Upload, Button, message, Space, Divider } from 'antd'
import { Upload as UploadIcon, FileText, CheckCircle, AlertCircle } from 'lucide-react'

export function PdfUploadPage() {
  const [uploading, setUploading] = useState(false)
  const [uploadedFiles, setUploadedFiles] = useState<{ name: string; status: 'success' | 'error' }[]>([])

  const handleUpload = async (file: File) => {
    if (!file.name.toLowerCase().endsWith('.pdf')) {
      message.error('只支持 PDF 文件')
      return
    }

    setUploading(true)
    try {
      const formData = new FormData()
      formData.append('file', file)
      formData.append('projectId', 'default')

      const response = await fetch('/api/chat/upload-norm', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('token')}`,
        },
        body: formData,
      })

      if (response.ok) {
        message.success(`${file.name} 上传成功`)
        setUploadedFiles(prev => [...prev, { name: file.name, status: 'success' }])
      } else {
        message.error(`${file.name} 上传失败`)
        setUploadedFiles(prev => [...prev, { name: file.name, status: 'error' }])
      }
    } catch {
      message.error(`${file.name} 上传失败`)
      setUploadedFiles(prev => [...prev, { name: file.name, status: 'error' }])
    } finally {
      setUploading(false)
    }
  }

  const uploadProps = {
    accept: '.pdf',
    showUploadList: false,
    beforeUpload: async (file: File) => {
      await handleUpload(file)
      return false
    },
  }

  return (
    <div style={{ maxWidth: 800, margin: '0 auto', padding: '32px 24px' }}>
      <div className="page-header">
        <div className="page-header__row">
          <div className="page-header__icon">
            <UploadIcon size={20} color="#06b6d4" />
          </div>
          <Typography.Title level={2} className="page-header__title">
            PDF 规范上传
          </Typography.Title>
        </div>
        <Typography.Paragraph className="page-header__desc">
          上传 PDF 格式规范文档，AI 将在代码审查和对话中引用这些规范。
        </Typography.Paragraph>
      </div>

      <Card
        style={{
          background: 'var(--bg-card)',
          border: '2px dashed var(--border-default)',
          borderRadius: 'var(--radius-lg)',
          textAlign: 'center',
          overflow: 'hidden',
        }}
        styles={{ body: { padding: '48px 24px' } }}
      >
        <Upload.Dragger {...uploadProps}>
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div
              style={{
                width: 64,
                height: 64,
                borderRadius: 'var(--radius-lg)',
                background: 'var(--aurora-gradient)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                margin: '0 auto',
                boxShadow: '0 8px 24px rgba(6,182,212,0.25)',
              }}
            >
              <UploadIcon style={{ width: 32, height: 32, color: 'white' }} />
            </div>
            <div>
              <Typography.Title level={4} style={{ margin: 0, color: 'var(--text-primary)', fontSize: 'var(--text-xl)' }}>
                点击或拖拽 PDF 文件到此处上传
              </Typography.Title>
              <Typography.Text style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                支持 .pdf 格式文件
              </Typography.Text>
            </div>
            <Button type="primary" loading={uploading} size="large" icon={<UploadIcon style={{ width: 18, height: 18 }} />}>
              选择文件
            </Button>
          </Space>
        </Upload.Dragger>
      </Card>

      {uploadedFiles.length > 0 && (
        <Card style={{
          marginTop: 24,
          background: 'var(--bg-card)',
          border: '1px solid var(--border-default)',
          borderRadius: 'var(--radius-lg)',
        }}>
          <Typography.Title level={5} style={{ marginBottom: 16, color: 'var(--text-primary)', fontSize: 'var(--text-lg)' }}>
            上传记录
          </Typography.Title>
          <Divider style={{ borderColor: 'var(--border-default)', margin: '12px 0' }} />
          {uploadedFiles.map((file, index) => (
            <div
              key={index}
              style={{
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '10px 12px', borderRadius: 'var(--radius-md)',
                transition: 'background 0.15s ease',
              }}
              onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'rgba(148,163,184,0.03)' }}
              onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'transparent' }}
            >
              <FileText style={{ width: 20, height: 20, color: '#60a5fa' }} />
              <Typography.Text style={{ flex: 1, color: 'var(--text-primary)' }}>{file.name}</Typography.Text>
              {file.status === 'success' ? (
                <CheckCircle style={{ width: 18, height: 18, color: '#22c55e' }} />
              ) : (
                <AlertCircle style={{ width: 18, height: 18, color: '#ef4444' }} />
              )}
            </div>
          ))}
        </Card>
      )}
    </div>
  )
}
