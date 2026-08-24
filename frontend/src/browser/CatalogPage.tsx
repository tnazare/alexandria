import { useCallback, useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { searchSkills } from '@/api/skills'
import { LoadingSpinner, Pagination } from '@/shared/components'
import { SearchBar } from './components/SearchBar'
import { FilterPanel } from './components/FilterPanel'
import type { FilterValues } from './components/FilterPanel'
import { SkillCard } from './components/SkillCard'
import { parseCatalogState, serializeCatalogState } from './utils/urlState'

const PAGE_SIZE = 12

/**
 * Catalog page with search input, filter panel, and paginated skill results.
 * State (search query, filters, page) is persisted as URL query parameters
 * for deep linking and bookmarking.
 */
export function CatalogPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  // Derive state from URL params
  const { query, filters, page } = useMemo(
    () => parseCatalogState(searchParams),
    [searchParams],
  )

  // Build API query params from URL-derived state
  const apiParams = useMemo(
    () => ({
      query: query || undefined,
      page,
      size: PAGE_SIZE,
      taskType: filters.taskType || undefined,
      language: filters.language || undefined,
      framework: filters.framework || undefined,
      tool: filters.tool || undefined,
      approvalState: filters.approvalState || undefined,
      riskLevel: filters.riskLevel || undefined,
      ownerTeam: filters.ownerTeam || undefined,
      visibility: filters.visibility || undefined,
    }),
    [query, page, filters],
  )

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['skills', apiParams],
    queryFn: () => searchSkills(apiParams),
  })

  const updateUrl = useCallback(
    (newQuery: string, newFilters: FilterValues, newPage: number) => {
      const params = serializeCatalogState({
        query: newQuery,
        filters: newFilters,
        page: newPage,
      })
      setSearchParams(params, { replace: true })
    },
    [setSearchParams],
  )

  const handleSearch = useCallback(
    (newQuery: string) => {
      updateUrl(newQuery, filters, 0)
    },
    [filters, updateUrl],
  )

  const handleFilterChange = useCallback(
    (newFilters: FilterValues) => {
      updateUrl(query, newFilters, 0)
    },
    [query, updateUrl],
  )

  const handlePageChange = useCallback(
    (newPage: number) => {
      updateUrl(query, filters, newPage)
    },
    [query, filters, updateUrl],
  )

  const handleSkillClick = useCallback(
    (skillId: string) => {
      void navigate(`/catalog/skills/${skillId}`)
    },
    [navigate],
  )

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h2 className="text-2xl font-bold text-neutral-900">Skill Catalog</h2>
        <p className="mt-1 text-sm text-neutral-600">
          Browse, search, and discover reusable AI coding workflows
        </p>
      </div>

      {/* Search */}
      <SearchBar value={query} onChange={handleSearch} />

      {/* Filters */}
      <FilterPanel filters={filters} onChange={handleFilterChange} />

      {/* Results */}
      <div>
        {isLoading && (
          <div className="flex justify-center py-12">
            <LoadingSpinner size="lg" />
          </div>
        )}

        {isError && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
            <p className="text-sm text-red-700">
              {error instanceof Error
                ? error.message
                : 'Failed to load skills. Please try again.'}
            </p>
          </div>
        )}

        {data && !isLoading && (
          <>
            {/* Results count */}
            <div className="mb-4 text-sm text-neutral-500">
              {data.totalElements === 0
                ? 'No skills found'
                : `${data.totalElements} skill${data.totalElements === 1 ? '' : 's'} found`}
            </div>

            {/* Skills grid */}
            {data.content.length > 0 ? (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {data.content.map((skill) => (
                  <SkillCard
                    key={skill.id}
                    skill={skill}
                    onClick={handleSkillClick}
                  />
                ))}
              </div>
            ) : (
              <div className="py-12 text-center">
                <EmptyIcon className="mx-auto h-12 w-12 text-neutral-300" />
                <p className="mt-4 text-sm text-neutral-500">
                  No skills match your search criteria.
                </p>
                <p className="mt-1 text-xs text-neutral-400">
                  Try adjusting your filters or search query.
                </p>
              </div>
            )}

            {/* Pagination */}
            <div className="mt-6">
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                onPageChange={handlePageChange}
              />
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function EmptyIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m5.231 13.481L15 17.25m-4.5-15H5.625c-.621 0-1.125.504-1.125 1.125v16.5c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Zm3.75 11.625a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
      />
    </svg>
  )
}
