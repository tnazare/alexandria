import type { RiskLevel } from '@/types/skill'

/** Tailwind color classes per risk level. */
export const RISK_BADGE_CLASSES: Record<RiskLevel, string> = {
  low: 'bg-green-100 text-green-800',
  medium: 'bg-yellow-100 text-yellow-800',
  high: 'bg-orange-100 text-orange-800',
  restricted: 'bg-red-100 text-red-800',
}

const RISK_LABELS: Record<RiskLevel, string> = {
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  restricted: 'Restricted',
}

interface RiskLevelBadgeProps {
  riskLevel: RiskLevel | null | undefined
  size?: 'sm' | 'md'
}

/**
 * Color-coded badge for a skill's risk level. Renders nothing when unset.
 */
export function RiskLevelBadge({ riskLevel, size = 'sm' }: RiskLevelBadgeProps) {
  if (!riskLevel) {
    return null
  }
  const sizeClasses = size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-sm'
  return (
    <span
      className={`inline-flex items-center rounded-full font-medium ${RISK_BADGE_CLASSES[riskLevel]} ${sizeClasses}`}
      data-testid="risk-level-badge"
      data-risk={riskLevel}
    >
      {RISK_LABELS[riskLevel]}
    </span>
  )
}
