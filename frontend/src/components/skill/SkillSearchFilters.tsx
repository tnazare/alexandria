import { useQuery } from '@tanstack/react-query'
import { listTeams } from '@/api/teams'
import { VISIBILITIES } from '@/types/skill'

const STATUSES = [
  'Draft',
  'Published',
  'Team_Approved',
  'Company_Approved',
  'Deprecated',
  'Archived',
  'Blocked',
] as const

export interface SkillFilterValues {
  search: string
  status: string
  visibility: string
  teamId: string
}

export const EMPTY_SKILL_FILTERS: SkillFilterValues = {
  search: '',
  status: '',
  visibility: '',
  teamId: '',
}

interface SkillSearchFiltersProps {
  values: SkillFilterValues
  onChange: (values: SkillFilterValues) => void
}

/**
 * Filter bar for the skill catalog: full-text search, lifecycle status,
 * visibility, and owning team.
 */
export function SkillSearchFilters({ values, onChange }: SkillSearchFiltersProps) {
  const { data: teamsPage } = useQuery({
    queryKey: ['teams', 'all-for-filter'],
    queryFn: () => listTeams(0, 100),
  })

  function update(patch: Partial<SkillFilterValues>) {
    onChange({ ...values, ...patch })
  }

  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <div className="lg:col-span-4">
        <label htmlFor="skill-search" className="sr-only">
          Search skills
        </label>
        <input
          id="skill-search"
          type="search"
          value={values.search}
          onChange={(e) => update({ search: e.target.value })}
          placeholder="Search skills…"
          className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        />
      </div>

      <div>
        <label htmlFor="filter-status" className="mb-1 block text-xs font-medium text-neutral-600">
          Status
        </label>
        <select
          id="filter-status"
          value={values.status}
          onChange={(e) => update({ status: e.target.value })}
          className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace('_', ' ')}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="filter-visibility" className="mb-1 block text-xs font-medium text-neutral-600">
          Visibility
        </label>
        <select
          id="filter-visibility"
          value={values.visibility}
          onChange={(e) => update({ visibility: e.target.value })}
          className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <option value="">All visibilities</option>
          {VISIBILITIES.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="filter-team" className="mb-1 block text-xs font-medium text-neutral-600">
          Team
        </label>
        <select
          id="filter-team"
          value={values.teamId}
          onChange={(e) => update({ teamId: e.target.value })}
          className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <option value="">All teams</option>
          {(teamsPage?.content ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}
