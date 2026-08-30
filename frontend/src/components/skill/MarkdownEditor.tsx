import { useState } from 'react'

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  id?: string
  label?: string
  placeholder?: string
  rows?: number
  ariaLabel?: string
}

type Mode = 'edit' | 'preview'

/**
 * Controlled Markdown text area with an edit/preview toggle.
 *
 * <p>No Markdown rendering library is bundled, so the preview shows the raw
 * Markdown source in a monospace, whitespace-preserving block. Content is
 * always rendered as text (never as HTML) to avoid injection.</p>
 */
export function MarkdownEditor({
  value,
  onChange,
  id = 'markdown-editor',
  label,
  placeholder = 'Write Markdown here…',
  rows = 12,
  ariaLabel = 'Markdown editor',
}: MarkdownEditorProps) {
  const [mode, setMode] = useState<Mode>('edit')

  return (
    <div className="rounded-md border border-neutral-300">
      <div className="flex items-center justify-between border-b border-neutral-200 bg-neutral-50 px-3 py-2">
        {label ? (
          <span className="text-sm font-medium text-neutral-700">{label}</span>
        ) : (
          <span />
        )}
        <div className="inline-flex overflow-hidden rounded-md border border-neutral-300 text-xs">
          <button
            type="button"
            onClick={() => setMode('edit')}
            aria-pressed={mode === 'edit'}
            className={`px-3 py-1 ${
              mode === 'edit'
                ? 'bg-primary-600 text-white'
                : 'bg-white text-neutral-700 hover:bg-neutral-100'
            }`}
          >
            Edit
          </button>
          <button
            type="button"
            onClick={() => setMode('preview')}
            aria-pressed={mode === 'preview'}
            className={`px-3 py-1 ${
              mode === 'preview'
                ? 'bg-primary-600 text-white'
                : 'bg-white text-neutral-700 hover:bg-neutral-100'
            }`}
          >
            Preview
          </button>
        </div>
      </div>

      {mode === 'edit' ? (
        <textarea
          id={id}
          aria-label={ariaLabel}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={rows}
          className="w-full resize-y rounded-b-md px-3 py-2 font-mono text-sm focus:outline-none focus:ring-2 focus:ring-inset focus:ring-primary-500"
        />
      ) : (
        <pre
          data-testid="markdown-preview"
          className="max-h-[32rem] overflow-auto whitespace-pre-wrap break-words rounded-b-md px-3 py-2 font-mono text-sm text-neutral-800"
        >
          {value || <span className="text-neutral-400">Nothing to preview.</span>}
        </pre>
      )}
    </div>
  )
}
