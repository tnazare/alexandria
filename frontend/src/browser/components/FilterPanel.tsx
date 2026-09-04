import { useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTaxonomy } from '@/api/taxonomy'
import { listTeams } from '@/api/teams'

/**
 * Active filter selections for the skill catalog.
 */
export interface FilterValues {
  taskType: string
  language: string
  framework: string
  tool: string
  approvalState: string
  riskLevel: string
  ownerTeam: string
  visibility: string
}

export const EMPTY_FILTERS: FilterValues = {
  taskType: '',
  language: '',
  framework: '',
  tool: '',
  approvalState: '',
  riskLevel: '',
  ownerTeam: '',
  visibility: '',
}

interface FilterPanelProps {
  filters: FilterValues
  onChange: (filters: FilterValues) => void
}

/**
 * Static, non-taxonomy filter options. Taxonomy dimensions (task type, language,
 * framework, tool) are fetched live from the taxonomy API; owner team from the teams API.
 */
const APPROVAL_STATE_OPTIONS = ['Draft', 'Published', 'Team_Approved', 'Company_Approved', 'Deprecated', 'Archived', 'Blocked']
const RISK_LEVEL_OPTIONS = ['low', 'medium', 'high', 'restricted']
const VISIBILITY_OPTIONS = ['private', 'team', 'organization', 'restricted']

/**
 * Filter panel for the skill catalog.
 * Provides select dropdowns for: task type, language, framework, tool,
 * approval state, risk level, owner team, and visibility.
 *
 * Taxonomy dimensions are populated from the live taxonomy API (active entries only);
 * owner team is a team dropdown keyed by team UUID.
 */
export function FilterPanel({ filters, onChange }: FilterPanelProps) {
  const { data: taxonomy, isLoading: taxonomyLoading } = useTaxonomy()
  const { data: teamsPage } = useQuery({
    queryKey: ['teams', 'all-for-filter'],
    queryFn: () => listTeams(0, 100),
  })

  const optionsFor = useCallback(
    (tagType: string): string[] =>
      (Array.isArray(taxonomy) ? taxonomy : [])
        .find((g) => g.tagType === tagType)
        ?.entries.filter((e) => e.active)
        .map((e) => e.value) ?? [],
    [taxonomy],
  )

  const teamOptions = (teamsPage?.content ?? []).map((t) => ({ value: t.id, label: t.name }))

  const updateFilter = useCallback(
    (key: keyof FilterValues, value: string) => {
      onChange({ ...filters, [key]: value })
    },
    [filters, onChange],
  )

  const hasActiveFilters = Object.values(filters).some((v) => v !== '')

  const clearAll = useCallback(() => {
    onChange(EMPTY_FILTERS)
  }, [onChange])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-neutral-700">Filters</h3>
        {hasActiveFilters && (
          <button
            type="button"
            onClick={clearAll}
            className="text-xs text-primary-600 hover:text-primary-700 underline"
          >
            Clear all
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <FilterSelect
          label="Task Type"
          value={filters.taskType}
          options={optionsFor('task_type')}
          disabled={taxonomyLoading}
          onChange={(v) => updateFilter('taskType', v)}
        />
        <FilterSelect
          label="Language"
          value={filters.language}
          options={optionsFor('language')}
          disabled={taxonomyLoading}
          onChange={(v) => updateFilter('language', v)}
        />
        <FilterSelect
          label="Framework"
          value={filters.framework}
          options={optionsFor('framework')}
          disabled={taxonomyLoading}
          onChange={(v) => updateFilter('framework', v)}
        />
        <FilterSelect
          label="Tool"
          value={filters.tool}
          options={optionsFor('tool')}
          disabled={taxonomyLoading}
          onChange={(v) => updateFilter('tool', v)}
        />
        <FilterSelect
          label="Approval State"
          value={filters.approvalState}
          options={APPROVAL_STATE_OPTIONS}
          onChange={(v) => updateFilter('approvalState', v)}
        />
        <FilterSelect
          label="Risk Level"
          value={filters.riskLevel}
          options={RISK_LEVEL_OPTIONS}
          onChange={(v) => updateFilter('riskLevel', v)}
        />
        <FilterSelect
          label="Owner Team"
          value={filters.ownerTeam}
          options={teamOptions}
          onChange={(v) => updateFilter('ownerTeam', v)}
        />
        <FilterSelect
          label="Visibility"
          value={filters.visibility}
          options={VISIBILITY_OPTIONS}
          onChange={(v) => updateFilter('visibility', v)}
        />
      </div>
    </div>
  )
}

/* Internal components */

type FilterOption = string | { value: string; label: string }

interface FilterSelectProps {
  label: string
  value: string
  options: FilterOption[]
  disabled?: boolean
  onChange: (value: string) => void
}

function FilterSelect({ label, value, options, disabled, onChange }: FilterSelectProps) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-500">
        {label}
      </label>
      <select
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-800 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 disabled:cursor-not-allowed disabled:bg-neutral-100 disabled:text-neutral-400"
      >
        <option value="">All</option>
        {options.map((opt) => {
          const optValue = typeof opt === 'string' ? opt : opt.value
          const optLabel = typeof opt === 'string' ? opt : opt.label
          return (
            <option key={optValue} value={optValue}>
              {optLabel}
            </option>
          )
        })}
      </select>
    </div>
  )
}
