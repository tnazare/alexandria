import type { FilterValues } from '@/browser/components/FilterPanel'

/**
 * Represents the full catalog URL state: search query + filter selections + pagination.
 */
export interface CatalogUrlState {
  query: string
  filters: FilterValues
  page: number
}

/** The filter field keys that are serialized to URL params. */
const FILTER_KEYS: ReadonlyArray<keyof FilterValues> = [
  'taskType',
  'language',
  'framework',
  'tool',
  'approvalState',
  'riskLevel',
  'ownerTeam',
  'visibility',
] as const

/**
 * Serializes a CatalogUrlState to URLSearchParams.
 * Empty/default values are omitted to keep URLs clean.
 */
export function serializeCatalogState(state: CatalogUrlState): URLSearchParams {
  const params = new URLSearchParams()

  if (state.query) {
    params.set('q', state.query)
  }

  for (const key of FILTER_KEYS) {
    const value = state.filters[key]
    if (value) {
      params.set(key, value)
    }
  }

  if (state.page > 0) {
    params.set('page', String(state.page))
  }

  return params
}

/**
 * Deserializes URLSearchParams back into a CatalogUrlState.
 * Missing keys default to empty string / 0.
 */
export function parseCatalogState(params: URLSearchParams): CatalogUrlState {
  const filters: FilterValues = {
    taskType: params.get('taskType') ?? '',
    language: params.get('language') ?? '',
    framework: params.get('framework') ?? '',
    tool: params.get('tool') ?? '',
    approvalState: params.get('approvalState') ?? '',
    riskLevel: params.get('riskLevel') ?? '',
    ownerTeam: params.get('ownerTeam') ?? '',
    visibility: params.get('visibility') ?? '',
  }

  return {
    query: params.get('q') ?? '',
    filters,
    page: Math.max(0, Number(params.get('page') ?? '0') || 0),
  }
}
