import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useSkill, useSkillVersions } from '@/api/skillsCatalog'
import { LoadingSpinner, Pagination } from '@/shared/components'
import type { SkillVersionResponse } from '@/types/skill'

const PAGE_SIZE = 20

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Paginated version history for a skill. Route: /catalog/skills/:skillId/versions
 */
export function SkillVersionsPage() {
  const { skillId } = useParams<{ skillId: string }>()
  const [page, setPage] = useState(0)

  const { data: skill } = useSkill(skillId)
  const { data, isLoading, isError } = useSkillVersions(skillId, page, PAGE_SIZE)

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }
  if (isError || !data) {
    return <div className="py-12 text-center text-neutral-600">Failed to load versions.</div>
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <div>
        <Link
          to={`/catalog/skills/${skillId}`}
          className="text-sm text-primary-600 hover:underline"
        >
          ← Back to skill
        </Link>
        <h1 className="mt-2 text-2xl font-bold text-neutral-900">
          Version history{skill ? `: ${skill.name}` : ''}
        </h1>
      </div>

      <VersionTimeline versions={data.content} skillId={skillId ?? ''} />

      <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
    </div>
  )
}

function VersionTimeline({
  versions,
  skillId,
}: {
  versions: SkillVersionResponse[]
  skillId: string
}) {
  if (versions.length === 0) {
    return <p className="text-sm text-neutral-500">No versions found.</p>
  }
  return (
    <ol className="space-y-3">
      {versions.map((version) => (
        <li
          key={version.id}
          className="rounded-md border border-neutral-200 p-4"
          data-testid="version-timeline-item"
        >
          <div className="flex items-center justify-between">
            <Link
              to={`/catalog/skills/${skillId}/versions/${version.versionNumber}`}
              className="font-mono text-sm font-semibold text-primary-700 hover:underline"
            >
              v{version.versionNumber}
            </Link>
            <span className="text-xs text-neutral-500">
              {version.publishedAt
                ? `Published ${formatDate(version.publishedAt)}`
                : `Created ${formatDate(version.createdAt)}`}
            </span>
          </div>
          {version.changelog && (
            <p className="mt-2 text-sm text-neutral-700">{version.changelog}</p>
          )}
        </li>
      ))}
    </ol>
  )
}
