import type { LifecycleStatus } from '@/api/skills'

/**
 * Configuration for each lifecycle status badge.
 * Each status has a distinct background color, text color, and icon.
 */
export interface BadgeConfig {
  label: string
  bgColor: string
  textColor: string
  icon: React.ReactNode
}

/**
 * Get the badge configuration for a given lifecycle status.
 * Exported for use in property-based tests.
 */
export function getBadgeConfig(status: LifecycleStatus): BadgeConfig {
  return BADGE_CONFIGS[status]
}

const BADGE_CONFIGS: Record<LifecycleStatus, BadgeConfig> = {
  Draft: {
    label: 'Draft',
    bgColor: 'bg-neutral-100',
    textColor: 'text-neutral-700',
    icon: <DraftIcon />,
  },
  Published: {
    label: 'Published',
    bgColor: 'bg-blue-100',
    textColor: 'text-blue-800',
    icon: <PublishedIcon />,
  },
  Team_Approved: {
    label: 'Team Approved',
    bgColor: 'bg-indigo-100',
    textColor: 'text-indigo-800',
    icon: <TeamApprovedIcon />,
  },
  Company_Approved: {
    label: 'Company Approved',
    bgColor: 'bg-green-100',
    textColor: 'text-green-800',
    icon: <CompanyApprovedIcon />,
  },
  Deprecated: {
    label: 'Deprecated',
    bgColor: 'bg-yellow-100',
    textColor: 'text-yellow-800',
    icon: <DeprecatedIcon />,
  },
  Archived: {
    label: 'Archived',
    bgColor: 'bg-neutral-200',
    textColor: 'text-neutral-600',
    icon: <ArchivedIcon />,
  },
  Blocked: {
    label: 'Blocked',
    bgColor: 'bg-red-100',
    textColor: 'text-red-800',
    icon: <BlockedIcon />,
  },
}

interface LifecycleBadgeProps {
  status: LifecycleStatus
  /** Show icon alongside the label. Defaults to true. */
  showIcon?: boolean
  /** Size variant. Defaults to 'sm'. */
  size?: 'sm' | 'md'
}

/**
 * Shared lifecycle badge component with distinct color and icon for each status.
 * Renders a visually distinct indicator for: Draft, Published, Team_Approved,
 * Company_Approved, Deprecated, Archived, and Blocked.
 */
export function LifecycleBadge({ status, showIcon = true, size = 'sm' }: LifecycleBadgeProps) {
  const config = BADGE_CONFIGS[status]

  const sizeClasses = size === 'sm'
    ? 'px-2 py-0.5 text-xs'
    : 'px-2.5 py-1 text-sm'

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full font-medium ${config.bgColor} ${config.textColor} ${sizeClasses}`}
      data-testid="lifecycle-badge"
      data-status={status}
    >
      {showIcon && <span className="shrink-0">{config.icon}</span>}
      {config.label}
    </span>
  )
}

/* --- Status Icons (inline SVGs for each lifecycle state) --- */

function DraftIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L6.832 19.82a4.5 4.5 0 0 1-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 0 1 1.13-1.897L16.863 4.487Z" />
    </svg>
  )
}

function PublishedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 0 0 8.716-6.747M12 21a9.004 9.004 0 0 1-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 0 1 7.843 4.582M12 3a8.997 8.997 0 0 0-7.843 4.582m15.686 0A11.953 11.953 0 0 1 12 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0 1 21 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0 1 12 16.5c-3.162 0-6.133-.815-8.716-2.247m0 0A9.015 9.015 0 0 1 3 12c0-1.605.42-3.113 1.157-4.418" />
    </svg>
  )
}

function TeamApprovedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M18 18.72a9.094 9.094 0 0 0 3.741-.479 3 3 0 0 0-4.682-2.72m.94 3.198.001.031c0 .225-.012.447-.037.666A11.944 11.944 0 0 1 12 21c-2.17 0-4.207-.576-5.963-1.584A6.062 6.062 0 0 1 6 18.719m12 0a5.971 5.971 0 0 0-.941-3.197m0 0A5.995 5.995 0 0 0 12 12.75a5.995 5.995 0 0 0-5.058 2.772m0 0a3 3 0 0 0-4.681 2.72 8.986 8.986 0 0 0 3.74.477m.94-3.197a5.971 5.971 0 0 0-.94 3.197M15 6.75a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm6 3a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Zm-13.5 0a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Z" />
    </svg>
  )
}

function CompanyApprovedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285Z" />
    </svg>
  )
}

function DeprecatedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
    </svg>
  )
}

function ArchivedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="m20.25 7.5-.625 10.632a2.25 2.25 0 0 1-2.247 2.118H6.622a2.25 2.25 0 0 1-2.247-2.118L3.75 7.5M10 11.25h4M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125Z" />
    </svg>
  )
}

function BlockedIcon() {
  return (
    <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M18.364 18.364A9 9 0 0 0 5.636 5.636m12.728 12.728A9 9 0 0 1 5.636 5.636m12.728 12.728L5.636 5.636" />
    </svg>
  )
}
