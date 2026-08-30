import type { LifecycleStatus } from '@/types/skill'

/** Tailwind color classes per lifecycle status. */
export const LIFECYCLE_BADGE_CLASSES: Record<LifecycleStatus, string> = {
  Draft: 'bg-neutral-100 text-neutral-700',
  Published: 'bg-green-100 text-green-800',
  Team_Approved: 'bg-blue-100 text-blue-800',
  Company_Approved: 'bg-indigo-100 text-indigo-800',
  Deprecated: 'bg-orange-100 text-orange-800',
  Archived: 'bg-neutral-200 text-neutral-600',
  Blocked: 'bg-red-100 text-red-800',
}

const LIFECYCLE_LABELS: Record<LifecycleStatus, string> = {
  Draft: 'Draft',
  Published: 'Published',
  Team_Approved: 'Team Approved',
  Company_Approved: 'Company Approved',
  Deprecated: 'Deprecated',
  Archived: 'Archived',
  Blocked: 'Blocked',
}

interface LifecycleStatusBadgeProps {
  status: LifecycleStatus
  size?: 'sm' | 'md'
}

/**
 * Color-coded badge for a skill's lifecycle status.
 */
export function LifecycleStatusBadge({ status, size = 'sm' }: LifecycleStatusBadgeProps) {
  const sizeClasses = size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-sm'
  return (
    <span
      className={`inline-flex items-center rounded-full font-medium ${LIFECYCLE_BADGE_CLASSES[status]} ${sizeClasses}`}
      data-testid="lifecycle-status-badge"
      data-status={status}
    >
      {LIFECYCLE_LABELS[status]}
    </span>
  )
}
