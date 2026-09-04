import { useMemo } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useSkills } from '@/api/skillsCatalog'
import { useDebounce } from '@/shared/hooks'
import { LoadingSpinner, Pagination, Button } from '@/shared/components'
import { LifecycleStatusBadge } from '@/components/skill/LifecycleStatusBadge'
import { RiskLevelBadge } from '@/components/skill/RiskLevelBadge'
import {
  SkillSearchFilters,
  type SkillFilterValues,
} from '@/components/skill/SkillSearchFilters'
import type { SkillSummaryResponse } from '@/types/skill'

const PAGE_SIZE = 12

function readFilters(params: URLSearchParams): SkillFilterValues {
  return {
    search: params.get('search') ?? '',
    status: params.get('status') ?? '',
    visibility: params.get('visibility') ?? '',
    riskLevel: params.get('riskLevel') ?? '',
    teamId: params.get('teamId') ?? '',
    taskType: params.get('taskType') ?? '',
    language: params.get('language') ?? '',
    framework: params.get('framework') ?? '',
    tool: params.get('tool') ?? '',
  }
}

function readPage(params: URLSearchParams): number {
  const raw = Number(params.get('page') ?? '0')
  return Number.isFinite(raw) && raw >= 0 ? raw : 0
}

/**
 * Skill catalog list with filters synced to URL query params.
 * Route: /catalog/skills
 */
export function SkillListPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const filters = useMemo(() => readFilters(searchParams), [searchParams])
  const page = useMemo(() => readPage(searchParams), [searchParams])
  const debouncedSearch = useDebounce(filters.search, 300)

  const { data, isLoading, isError } = useSkills({
    page,
    size: PAGE_SIZE,
    search: debouncedSearch || undefined,
    status: filters.status || undefined,
    visibility: filters.visibility || undefined,
    riskLevel: filters.riskLevel || undefined,
    teamId: filters.teamId || undefined,
    taskType: filters.taskType || undefined,
    language: filters.language || undefined,
    framework: filters.framework || undefined,
    tool: filters.tool || undefined,
  })

  function writeParams(next: SkillFilterValues, nextPage: number) {
    const params = new URLSearchParams()
    if (next.search) params.set('search', next.search)
    if (next.status) params.set('status', next.status)
    if (next.visibility) params.set('visibility', next.visibility)
    if (next.riskLevel) params.set('riskLevel', next.riskLevel)
    if (next.teamId) params.set('teamId', next.teamId)
    if (next.taskType) params.set('taskType', next.taskType)
    if (next.language) params.set('language', next.language)
    if (next.framework) params.set('framework', next.framework)
    if (next.tool) params.set('tool', next.tool)
    if (nextPage > 0) params.set('page', String(nextPage))
    setSearchParams(params, { replace: true })
  }

  function handleFilterChange(next: SkillFilterValues) {
    writeParams(next, 0)
  }

  function handlePageChange(nextPage: number) {
    writeParams(filters, nextPage)
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-neutral-900">Skills</h2>
          <p className="mt-1 text-sm text-neutral-600">
            Search, filter, and manage reusable AI coding workflows
          </p>
        </div>
        <Button onClick={() => navigate('/catalog/skills/new')}>New skill</Button>
      </div>

      <SkillSearchFilters values={filters} onChange={handleFilterChange} />

      {isLoading && (
        <div className="flex justify-center py-12">
          <LoadingSpinner size="lg" />
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center text-sm text-red-700">
          Failed to load skills. Please try again.
        </div>
      )}

      {data && !isLoading && (
        <>
          <p className="text-sm text-neutral-500">
            {data.totalElements === 0
              ? 'No skills found'
              : `${data.totalElements} skill${data.totalElements === 1 ? '' : 's'} found`}
          </p>

          {data.content.length > 0 ? (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {data.content.map((skill) => (
                <SkillSummaryCard key={skill.id} skill={skill} />
              ))}
            </div>
          ) : (
            <div className="py-12 text-center text-sm text-neutral-500">
              No skills match your filters.
            </div>
          )}

          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            onPageChange={handlePageChange}
          />
        </>
      )}
    </div>
  )
}

function SkillSummaryCard({ skill }: { skill: SkillSummaryResponse }) {
  return (
    <Link
      to={`/catalog/skills/${skill.id}`}
      className="block rounded-lg border border-neutral-200 bg-white p-4 transition-shadow hover:shadow-md"
    >
      <div className="mb-2 flex items-start justify-between gap-2">
        <h3 className="font-semibold text-neutral-900">{skill.name}</h3>
        <LifecycleStatusBadge status={skill.lifecycleStatus} />
      </div>
      <p className="mb-3 line-clamp-2 text-sm text-neutral-600">{skill.description}</p>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <RiskLevelBadge riskLevel={skill.riskLevel} />
        {skill.tags.slice(0, 3).map((tag) => (
          <span
            key={tag.id}
            className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs text-neutral-600"
          >
            {tag.value}
          </span>
        ))}
      </div>
      <div className="flex items-center justify-between text-xs text-neutral-400">
        <span>{skill.ownerTeamName ?? 'No team'}</span>
        <span>Updated {new Date(skill.updatedAt).toLocaleDateString()}</span>
      </div>
    </Link>
  )
}
