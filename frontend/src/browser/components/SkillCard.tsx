import type { SkillSummary } from '@/api/skills'
import { LifecycleBadge } from './LifecycleBadge'

interface SkillCardProps {
  skill: SkillSummary
  onClick: (skillId: string) => void
}

/**
 * Card component displaying a skill summary in the catalog grid.
 * Shows: name, description, owner team, lifecycle status, approval state,
 * tools, risk level, and last updated date.
 */
export function SkillCard({ skill, onClick }: SkillCardProps) {
  return (
    <article
      className="group cursor-pointer rounded-lg border border-neutral-200 bg-white p-5 shadow-sm transition-all hover:border-primary-300 hover:shadow-md"
      onClick={() => onClick(skill.id)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onClick(skill.id)
        }
      }}
      role="button"
      tabIndex={0}
      aria-label={`View skill: ${skill.name}`}
    >
      {/* Header: Name + Risk Level */}
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-sm font-semibold text-neutral-900 group-hover:text-primary-700 line-clamp-1">
          {skill.name}
        </h3>
        <RiskBadge level={skill.riskLevel} />
      </div>

      {/* Description */}
      <p className="mt-2 text-sm text-neutral-600 line-clamp-2">
        {skill.description ?? 'No description available'}
      </p>

      {/* Metadata row: Owner team + Lifecycle + Approval */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {skill.ownerTeam && (
          <span className="inline-flex items-center gap-1 rounded-full bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
            <TeamIcon className="h-3 w-3" />
            {skill.ownerTeam}
          </span>
        )}
        <LifecycleBadge status={skill.lifecycleStatus} showIcon={false} />
        <span className="rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700">
          {skill.approvalState}
        </span>
      </div>

      {/* Tools */}
      {skill.tools.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1">
          {skill.tools.map((tool) => (
            <span
              key={tool}
              className="rounded bg-neutral-50 px-1.5 py-0.5 text-xs text-neutral-600 border border-neutral-200"
            >
              {tool}
            </span>
          ))}
        </div>
      )}

      {/* Footer: Last updated */}
      <div className="mt-3 text-xs text-neutral-400">
        Updated {formatDate(skill.lastUpdated)}
      </div>
    </article>
  )
}

/* Helper components */

function RiskBadge({ level }: { level: string }) {
  const styles: Record<string, string> = {
    low: 'bg-green-50 text-green-700',
    medium: 'bg-yellow-50 text-yellow-700',
    high: 'bg-orange-50 text-orange-700',
    restricted: 'bg-red-50 text-red-700',
  }

  return (
    <span
      className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${styles[level] ?? 'bg-neutral-100 text-neutral-600'}`}
    >
      {level}
    </span>
  )
}

function TeamIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M18 18.72a9.094 9.094 0 0 0 3.741-.479 3 3 0 0 0-4.682-2.72m.94 3.198.001.031c0 .225-.012.447-.037.666A11.944 11.944 0 0 1 12 21c-2.17 0-4.207-.576-5.963-1.584A6.062 6.062 0 0 1 6 18.719m12 0a5.971 5.971 0 0 0-.941-3.197m0 0A5.995 5.995 0 0 0 12 12.75a5.995 5.995 0 0 0-5.058 2.772m0 0a3 3 0 0 0-4.681 2.72 8.986 8.986 0 0 0 3.74.477m.94-3.197a5.971 5.971 0 0 0-.94 3.197M15 6.75a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm6 3a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Zm-13.5 0a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Z"
      />
    </svg>
  )
}

function formatDate(dateStr: string): string {
  try {
    const date = new Date(dateStr)
    return date.toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    })
  } catch {
    return dateStr
  }
}
