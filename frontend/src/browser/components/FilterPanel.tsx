import { useCallback } from 'react'

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
 * Filter options for each dimension.
 * In a real app, these could be fetched from the taxonomy API.
 */
const TASK_TYPE_OPTIONS = ['Generation', 'Refactoring', 'Testing', 'Review', 'Documentation', 'Debugging']
const LANGUAGE_OPTIONS = ['TypeScript', 'JavaScript', 'Java', 'Python', 'Go', 'Rust', 'C#', 'Ruby']
const FRAMEWORK_OPTIONS = ['React', 'Angular', 'Vue', 'Spring Boot', 'Django', 'Express', 'Next.js']
const TOOL_OPTIONS = ['GitHub Copilot', 'Codex', 'Cursor', 'Kiro', 'Claude Code', 'Cody']
const APPROVAL_STATE_OPTIONS = ['Draft', 'Published', 'Team_Approved', 'Company_Approved', 'Deprecated', 'Archived', 'Blocked']
const RISK_LEVEL_OPTIONS = ['low', 'medium', 'high', 'restricted']
const VISIBILITY_OPTIONS = ['private', 'team', 'organization', 'restricted']

/**
 * Filter panel for the skill catalog.
 * Provides select dropdowns for: task type, language, framework, tool,
 * approval state, risk level, owner team, and visibility.
 */
export function FilterPanel({ filters, onChange }: FilterPanelProps) {
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
          options={TASK_TYPE_OPTIONS}
          onChange={(v) => updateFilter('taskType', v)}
        />
        <FilterSelect
          label="Language"
          value={filters.language}
          options={LANGUAGE_OPTIONS}
          onChange={(v) => updateFilter('language', v)}
        />
        <FilterSelect
          label="Framework"
          value={filters.framework}
          options={FRAMEWORK_OPTIONS}
          onChange={(v) => updateFilter('framework', v)}
        />
        <FilterSelect
          label="Tool"
          value={filters.tool}
          options={TOOL_OPTIONS}
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
        <FilterInput
          label="Owner Team"
          value={filters.ownerTeam}
          placeholder="Team name..."
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

interface FilterSelectProps {
  label: string
  value: string
  options: string[]
  onChange: (value: string) => void
}

function FilterSelect({ label, value, options, onChange }: FilterSelectProps) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-500">
        {label}
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-800 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20"
      >
        <option value="">All</option>
        {options.map((opt) => (
          <option key={opt} value={opt}>
            {opt}
          </option>
        ))}
      </select>
    </div>
  )
}

interface FilterInputProps {
  label: string
  value: string
  placeholder: string
  onChange: (value: string) => void
}

function FilterInput({ label, value, placeholder, onChange }: FilterInputProps) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-500">
        {label}
      </label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-800 placeholder:text-neutral-400 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20"
      />
    </div>
  )
}
