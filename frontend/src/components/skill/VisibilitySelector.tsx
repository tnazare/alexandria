import type { Visibility } from '@/types/skill'
import { VISIBILITIES } from '@/types/skill'

/** Human-readable label and description for each visibility level. */
export const VISIBILITY_OPTIONS: Record<Visibility, { label: string; description: string }> = {
  private: {
    label: 'Private',
    description: 'Only the author and organization admins can see this skill.',
  },
  team: {
    label: 'Team',
    description: 'Members of the owning team and organization admins can see this skill.',
  },
  organization: {
    label: 'Organization',
    description: 'All authenticated users in the organization can see this skill.',
  },
  restricted: {
    label: 'Restricted',
    description: 'Only explicitly granted users can see this skill.',
  },
}

interface VisibilitySelectorProps {
  value: Visibility
  onChange: (value: Visibility) => void
  id?: string
  disabled?: boolean
}

/**
 * Dropdown for choosing a skill's visibility. Shows a description of the
 * selected option below the control.
 */
export function VisibilitySelector({
  value,
  onChange,
  id = 'visibility-selector',
  disabled = false,
}: VisibilitySelectorProps) {
  return (
    <div>
      <select
        id={id}
        aria-label="Visibility"
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value as Visibility)}
        className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:bg-neutral-100"
      >
        {VISIBILITIES.map((v) => (
          <option key={v} value={v}>
            {VISIBILITY_OPTIONS[v].label}
          </option>
        ))}
      </select>
      <p className="mt-1 text-xs text-neutral-500">
        {VISIBILITY_OPTIONS[value].description}
      </p>
    </div>
  )
}
