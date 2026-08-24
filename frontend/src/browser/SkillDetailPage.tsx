import { useParams, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getSkill, exportSkill } from '@/api/skills'
import type { ExportFormat, SkillDetail, SkillExample, SkillVariant, SkillVersionSummary } from '@/api/skills'
import { Button } from '@/shared/components/Button'
import { LoadingSpinner } from '@/shared/components/LoadingSpinner'
import { LifecycleBadge } from './components/LifecycleBadge'

// --- Export format definitions ---

interface ExportOption {
  format: ExportFormat
  label: string
  action: 'copy' | 'download'
  filename?: string
}

const EXPORT_OPTIONS: ExportOption[] = [
  { format: 'generic', label: 'Copy generic prompt', action: 'copy' },
  { format: 'github_copilot', label: 'Copy for GitHub Copilot', action: 'copy' },
  { format: 'codex', label: 'Copy for Codex', action: 'copy' },
  { format: 'markdown', label: 'Export as Markdown', action: 'download', filename: 'skill.md' },
  { format: 'agents_md', label: 'Export as AGENTS.md', action: 'download', filename: 'AGENTS.md' },
  { format: 'cursor_rule', label: 'Export as Cursor rule', action: 'download', filename: '.cursorrules' },
  { format: 'kiro_steering', label: 'Export as Kiro steering fragment', action: 'download', filename: 'steering.md' },
]

// --- Helper functions ---

async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // Fallback for older browsers or non-secure contexts
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const success = document.execCommand('copy')
    document.body.removeChild(textarea)
    return success
  }
}

function triggerFileDownload(content: string, filename: string): void {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

// --- Sub-components ---

function RiskBadge({ level }: { level: string }) {
  const styles: Record<string, string> = {
    low: 'bg-green-100 text-green-800',
    medium: 'bg-yellow-100 text-yellow-800',
    high: 'bg-orange-100 text-orange-800',
    restricted: 'bg-red-100 text-red-800',
  }

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[level] ?? 'bg-neutral-100 text-neutral-800'}`}
    >
      {level}
    </span>
  )
}



function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h3 className="text-lg font-semibold text-neutral-900">{title}</h3>
      <div className="text-neutral-700">{children}</div>
    </section>
  )
}

function VariantCard({ variant }: { variant: SkillVariant }) {
  return (
    <div className="rounded-lg border border-neutral-200 p-4">
      <h4 className="mb-2 text-sm font-semibold text-neutral-900">{variant.tool}</h4>
      <pre className="whitespace-pre-wrap rounded bg-neutral-50 p-3 text-sm text-neutral-700">
        {variant.instructions}
      </pre>
    </div>
  )
}

function ExampleCard({ example }: { example: SkillExample }) {
  return (
    <div className="rounded-lg border border-neutral-200 p-4 space-y-2">
      <h4 className="text-sm font-semibold text-neutral-900">{example.title}</h4>
      {example.description && (
        <p className="text-sm text-neutral-600">{example.description}</p>
      )}
      <div className="space-y-1">
        <p className="text-xs font-medium text-neutral-500 uppercase">Input</p>
        <pre className="whitespace-pre-wrap rounded bg-neutral-50 p-2 text-sm text-neutral-700">
          {example.input}
        </pre>
      </div>
      <div className="space-y-1">
        <p className="text-xs font-medium text-neutral-500 uppercase">Expected Output</p>
        <pre className="whitespace-pre-wrap rounded bg-neutral-50 p-2 text-sm text-neutral-700">
          {example.expectedOutput}
        </pre>
      </div>
    </div>
  )
}

function VersionHistoryTable({ versions }: { versions: SkillVersionSummary[] }) {
  if (versions.length === 0) {
    return <p className="text-sm text-neutral-500">No version history available.</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="border-b border-neutral-200 text-left text-neutral-500">
            <th className="pb-2 pr-4 font-medium">Version</th>
            <th className="pb-2 pr-4 font-medium">Date</th>
            <th className="pb-2 pr-4 font-medium">Author</th>
            <th className="pb-2 font-medium">Description</th>
          </tr>
        </thead>
        <tbody>
          {versions.map((v) => (
            <tr key={v.version} className="border-b border-neutral-100">
              <td className="py-2 pr-4 font-mono text-neutral-900">{v.version}</td>
              <td className="py-2 pr-4 text-neutral-600">{formatDate(v.createdAt)}</td>
              <td className="py-2 pr-4 text-neutral-600">{v.createdBy}</td>
              <td className="py-2 text-neutral-600">{v.changeDescription ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FeedbackSection({ summary }: { summary: SkillDetail['feedbackSummary'] }) {
  if (!summary) {
    return <p className="text-sm text-neutral-500">No feedback yet.</p>
  }

  return (
    <div className="flex gap-6">
      <div className="text-center">
        <p className="text-2xl font-bold text-neutral-900">
          {summary.averageRating.toFixed(1)}
        </p>
        <p className="text-xs text-neutral-500">Avg Rating</p>
      </div>
      <div className="text-center">
        <p className="text-2xl font-bold text-neutral-900">{summary.totalRatings}</p>
        <p className="text-xs text-neutral-500">Ratings</p>
      </div>
      <div className="text-center">
        <p className="text-2xl font-bold text-neutral-900">{summary.totalComments}</p>
        <p className="text-xs text-neutral-500">Comments</p>
      </div>
    </div>
  )
}

function ReviewStatusSection({ review }: { review: SkillDetail['reviewStatus'] }) {
  if (!review) {
    return <p className="text-sm text-neutral-500">No review information available.</p>
  }

  const stateStyles: Record<string, string> = {
    approved: 'text-green-700',
    rejected: 'text-red-700',
    pending: 'text-yellow-700',
  }

  return (
    <div className="space-y-1 text-sm">
      <p>
        <span className="font-medium text-neutral-600">State: </span>
        <span className={`font-semibold capitalize ${stateStyles[review.state] ?? 'text-neutral-700'}`}>
          {review.state}
        </span>
      </p>
      {review.reviewedBy && (
        <p>
          <span className="font-medium text-neutral-600">Reviewed by: </span>
          <span className="text-neutral-800">{review.reviewedBy}</span>
        </p>
      )}
      {review.reviewedAt && (
        <p>
          <span className="font-medium text-neutral-600">Reviewed on: </span>
          <span className="text-neutral-800">{formatDate(review.reviewedAt)}</span>
        </p>
      )}
      {review.comments && (
        <p>
          <span className="font-medium text-neutral-600">Comments: </span>
          <span className="text-neutral-800">{review.comments}</span>
        </p>
      )}
    </div>
  )
}

// --- Export Actions Component ---

function ExportActions({ skillId, skillName }: { skillId: string; skillName: string }) {
  const [loadingFormat, setLoadingFormat] = useState<ExportFormat | null>(null)
  const [successFormat, setSuccessFormat] = useState<ExportFormat | null>(null)

  async function handleExport(option: ExportOption) {
    setLoadingFormat(option.format)
    setSuccessFormat(null)

    try {
      const content = await exportSkill(skillId, option.format)

      if (option.action === 'copy') {
        const success = await copyToClipboard(content)
        if (success) {
          setSuccessFormat(option.format)
          setTimeout(() => setSuccessFormat(null), 2000)
        }
      } else {
        const filename = option.filename ?? `${skillName}.md`
        triggerFileDownload(content, filename)
        setSuccessFormat(option.format)
        setTimeout(() => setSuccessFormat(null), 2000)
      }
    } catch {
      // Error is already handled by the API client interceptor
    } finally {
      setLoadingFormat(null)
    }
  }

  return (
    <div className="space-y-3">
      <h3 className="text-lg font-semibold text-neutral-900">Export & Copy</h3>
      <div className="flex flex-wrap gap-2">
        {EXPORT_OPTIONS.map((option) => (
          <Button
            key={option.format}
            variant="secondary"
            size="sm"
            loading={loadingFormat === option.format}
            disabled={loadingFormat !== null}
            onClick={() => handleExport(option)}
          >
            {successFormat === option.format ? (
              <>
                <CheckIcon />
                {option.action === 'copy' ? 'Copied!' : 'Downloaded!'}
              </>
            ) : (
              <>
                {option.action === 'copy' ? <ClipboardIcon /> : <DownloadIcon />}
                {option.label}
              </>
            )}
          </Button>
        ))}
      </div>
    </div>
  )
}

// --- Icons ---

function ClipboardIcon() {
  return (
    <svg
      className="h-4 w-4"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9.75a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184"
      />
    </svg>
  )
}

function DownloadIcon() {
  return (
    <svg
      className="h-4 w-4"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3"
      />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg
      className="h-4 w-4 text-green-600"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={2}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
    </svg>
  )
}

function ArrowLeftIcon() {
  return (
    <svg
      className="h-4 w-4"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
    </svg>
  )
}

// --- Main Page Component ---

export function SkillDetailPage() {
  const { skillId } = useParams<{ skillId: string }>()
  const navigate = useNavigate()

  const {
    data: skill,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery<SkillDetail>({
    queryKey: ['skill', skillId],
    queryFn: () => getSkill(skillId!),
    enabled: !!skillId,
  })

  if (isLoading) {
    return (
      <div className="flex min-h-[400px] items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex min-h-[400px] flex-col items-center justify-center gap-4">
        <p className="text-neutral-600">
          {(error as Error)?.message ?? 'Failed to load skill details.'}
        </p>
        <Button variant="secondary" onClick={() => refetch()}>
          Retry
        </Button>
      </div>
    )
  }

  if (!skill) {
    return (
      <div className="flex min-h-[400px] items-center justify-center">
        <p className="text-neutral-600">Skill not found.</p>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-6">
      {/* Back navigation */}
      <button
        onClick={() => navigate('/catalog')}
        className="inline-flex items-center gap-1 text-sm text-neutral-600 hover:text-neutral-900 transition-colors"
      >
        <ArrowLeftIcon />
        Back to catalog
      </button>

      {/* Header */}
      <header className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-neutral-900">{skill.name}</h1>
          <LifecycleBadge status={skill.lifecycleStatus} size="md" />
          <RiskBadge level={skill.riskLevel} />
        </div>
        {skill.description && (
          <p className="text-neutral-600">{skill.description}</p>
        )}
        <div className="flex flex-wrap gap-4 text-sm text-neutral-500">
          {skill.ownerTeam && <span>Team: {skill.ownerTeam}</span>}
          <span>Updated: {formatDate(skill.updatedAt)}</span>
          {skill.supportedTools.length > 0 && (
            <span>Tools: {skill.supportedTools.join(', ')}</span>
          )}
        </div>
      </header>

      {/* Export Actions */}
      <ExportActions skillId={skill.id} skillName={skill.name} />

      {/* Overview */}
      {skill.overview && (
        <Section title="Overview">
          <p className="whitespace-pre-wrap">{skill.overview}</p>
        </Section>
      )}

      {/* Instructions */}
      {skill.instructions && (
        <Section title="Instructions">
          <pre className="whitespace-pre-wrap rounded-lg bg-neutral-50 p-4 text-sm">
            {skill.instructions}
          </pre>
        </Section>
      )}

      {/* Variants */}
      {skill.variants.length > 0 && (
        <Section title="Tool-Specific Variants">
          <div className="grid gap-4 md:grid-cols-2">
            {skill.variants.map((variant) => (
              <VariantCard key={variant.tool} variant={variant} />
            ))}
          </div>
        </Section>
      )}

      {/* Applicability */}
      {skill.applicability && (
        <Section title="Applicability">
          <p className="whitespace-pre-wrap">{skill.applicability}</p>
        </Section>
      )}

      {/* Examples */}
      {skill.examples.length > 0 && (
        <Section title="Examples">
          <div className="space-y-4">
            {skill.examples.map((example, idx) => (
              <ExampleCard key={idx} example={example} />
            ))}
          </div>
        </Section>
      )}

      {/* Limitations */}
      {skill.limitations && (
        <Section title="Limitations">
          <p className="whitespace-pre-wrap">{skill.limitations}</p>
        </Section>
      )}

      {/* Risk Level */}
      <Section title="Risk Level">
        <div className="flex items-center gap-2">
          <RiskBadge level={skill.riskLevel} />
          <span className="text-sm text-neutral-600 capitalize">{skill.riskLevel} risk</span>
        </div>
      </Section>

      {/* Review Status */}
      <Section title="Review Status">
        <ReviewStatusSection review={skill.reviewStatus} />
      </Section>

      {/* Version History */}
      <Section title="Version History">
        <VersionHistoryTable versions={skill.versionHistory} />
      </Section>

      {/* Feedback Summary */}
      <Section title="Feedback Summary">
        <FeedbackSection summary={skill.feedbackSummary} />
      </Section>
    </div>
  )
}
