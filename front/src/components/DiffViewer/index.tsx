import { useMemo } from 'react'
import { Card } from 'antd'
import { FileCode } from 'lucide-react'
import './DiffViewer.css'

interface Props {
  diff?: string
}

interface DiffRow {
  type: 'file' | 'hunk' | 'context' | 'add' | 'del'
  oldLine?: number
  newLine?: number
  marker?: string
  content: string
}

interface DiffFile {
  path: string
  rows: DiffRow[]
}

export function DiffViewer({ diff }: Props) {
  const files = useMemo(() => parseDiff(diff ?? ''), [diff])

  if (!diff?.trim() || files.length === 0) {
    return (
      <Card size="small" title="Diff预览">
        <div className="diff-empty">暂无Diff内容</div>
      </Card>
    )
  }

  return (
    <Card size="small" title="Diff预览">
      <div className="diff-viewer">
        {files.map((file) => (
          <div key={file.path} className="diff-file">
            <div className="diff-file-header">
              <FileCode size={16} color="#06b6d4" />
              {file.path}
            </div>
            {file.rows.map((row, index) => {
              const key = `${row.type}-${index}`
              if (row.type === 'hunk') {
                return (
                  <div key={key} className="diff-hunk-header">
                    <div className="diff-gutter">
                      <span className="diff-line-old" />
                      <span className="diff-line-new" />
                      <span className="diff-marker" />
                    </div>
                    <div className="diff-content">{row.content}</div>
                  </div>
                )
              }

              return (
                <div key={key} className={`diff-row ${row.type}`}>
                  <div className="diff-gutter">
                    <span className="diff-line-old">{row.oldLine ?? ''}</span>
                    <span className="diff-line-new">{row.newLine ?? ''}</span>
                    <span className="diff-marker">{row.marker ?? ''}</span>
                  </div>
                  <div className="diff-content">{row.content}</div>
                </div>
              )
            })}
          </div>
        ))}
      </div>
    </Card>
  )
}

function parseDiff(raw: string): DiffFile[] {
  const lines = raw.split(/\r?\n/)
  const files: DiffFile[] = []
  let currentFile: DiffFile | null = null
  let oldLine = 0
  let newLine = 0
  let inHunk = false

  for (const rawLine of lines) {
    const line = rawLine.replace(/\r$/, '')
    if (!line.trim()) {
      continue
    }

    if (line.startsWith('diff --git ')) {
      const path = extractPathFromDiffHeader(line)
      currentFile = { path, rows: [] }
      files.push(currentFile)
      inHunk = false
      continue
    }

    if (isFilePathLine(line) && !line.startsWith('@@') && !line.startsWith('+') && !line.startsWith('-') && !line.startsWith(' ')) {
      currentFile = { path: line.trim(), rows: [] }
      files.push(currentFile)
      inHunk = false
      continue
    }

    if (!currentFile) {
      continue
    }

    if (line.startsWith('@@')) {
      const hunk = parseHunkHeader(line)
      oldLine = hunk.oldStart
      newLine = hunk.newStart
      inHunk = true
      currentFile.rows.push({
        type: 'hunk',
        content: extractHunkHeaderDisplay(line),
      })
      continue
    }

    if (!inHunk) {
      continue
    }

    if (line.startsWith('+')) {
      currentFile.rows.push({
        type: 'add',
        newLine,
        marker: '+',
        content: line.slice(1),
      })
      newLine += 1
      continue
    }

    if (line.startsWith('-')) {
      currentFile.rows.push({
        type: 'del',
        oldLine,
        marker: '-',
        content: line.slice(1),
      })
      oldLine += 1
      continue
    }

    if (line.startsWith(' ') || line.startsWith('\t')) {
      currentFile.rows.push({
        type: 'context',
        oldLine,
        newLine,
        marker: ' ',
        content: line.slice(1),
      })
      oldLine += 1
      newLine += 1
      continue
    }

    if (line.startsWith('\\')) {
      continue
    }

    currentFile.rows.push({
      type: 'context',
      oldLine,
      newLine,
      marker: ' ',
      content: line,
    })
    oldLine += 1
    newLine += 1
  }

  return files
}

function extractPathFromDiffHeader(line: string): string {
  const match = /diff --git a\/(.+?) b\/(.+)/.exec(line)
  if (match) {
    return match[2]
  }
  return line
}

function isFilePathLine(line: string): boolean {
  return /^[\w./\-~][\w./\-~ ]*[\w./\-~]$/.test(line) && line.includes('/')
}

function parseHunkHeader(line: string): { oldStart: number; newStart: number } {
  const match = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line)
  if (match) {
    return {
      oldStart: parseInt(match[1], 10),
      newStart: parseInt(match[2], 10),
    }
  }
  return { oldStart: 0, newStart: 0 }
}

function extractHunkHeaderDisplay(line: string): string {
  const match = /(@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@)/.exec(line)
  return match ? match[1] : line
}
