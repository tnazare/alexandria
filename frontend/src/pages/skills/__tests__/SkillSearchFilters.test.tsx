import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import type { ReactNode } from 'react'
import type { PageResponse } from '@/shared/types'
import type { SkillSummaryResponse } from '@/types/skill'
import type { TaxonomyGroupResponse } from '@/types/taxonomy'
import {
  parseCatalogState,
  serializeCatalogState,
} from '@/browser/utils/urlState'
import type { FilterValues } from '@/browser/components/FilterPanel'

const get = vi.fn()
vi.mock('@/api/client', () => ({
  apiClient: {
    get: (...args: unknown[]) => get(...args),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}))

import { SkillListPage } from '../SkillListPage'

// --- Fixtures ---

function pageOf(content: SkillSummaryResponse[]): PageResponse<SkillSummaryResponse> {
  return { content, page: 0, size: 12, totalElements: content.length, totalPages: 1 }
}

function summary(name: string): SkillSummaryResponse {
  return {
    id: name,
    name,
    slug: name.toLowerCase(),
    description: `${name} description`,
    ownerTeamName: 'Team',
    lifecycleStatus: 'Published',
    deprecationReason: null,
    visibility: 'organization',
    riskLevel: 'low',
    currentVersionNumber: 1,
    tags: [],
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

const EMPTY_TEAMS = {
  data: { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 },
}

const TAXONOMY: TaxonomyGroupResponse[] = [
  {
    tagType: 'task_type',
    entries: [
      { id: '1', tagType: 'task_type', value: 'code-review', description: null, active: true, createdAt: '' },
      { id: '2', tagType: 'task_type', value: 'legacy-inactive', description: null, active: false, createdAt: '' },
    ],
  },
  {
    tagType: 'language',
    entries: [
      { id: '3', tagType: 'language', value: 'python', description: null, active: true, createdAt: '' },
    ],
  },
]

interface RouteOptions {
  skills?: PageResponse<SkillSummaryResponse>
  taxonomy?: unknown
  taxonomyRejects?: boolean
}

function routeGet(opts: RouteOptions = {}) {
  const skills = opts.skills ?? pageOf([summary('Alpha')])
  get.mockImplementation((url: string) => {
    if (url === '/api/teams') return Promise.resolve(EMPTY_TEAMS)
    if (url === '/api/skills') return Promise.resolve({ data: skills })
    if (url === '/api/taxonomy') {
      if (opts.taxonomyRejects) return Promise.reject(new Error('taxonomy down'))
      return Promise.resolve({ data: opts.taxonomy ?? TAXONOMY })
    }
    return Promise.resolve({ data: {} })
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/catalog/skills']}>{children}</MemoryRouter>
    </QueryClientProvider>
  )
  return render(<SkillListPage />, { wrapper })
}

describe('Skill search filters', () => {
  beforeEach(() => {
    get.mockReset()
  })

  // 8.1 — filter params forwarded to the API
  it('forwards the taskType filter to the API query', async () => {
    routeGet()
    renderPage()
    await screen.findByText('Alpha')

    fireEvent.change(screen.getByLabelText('Task type'), { target: { value: 'code-review' } })

    await waitFor(() => {
      const skillCalls = get.mock.calls.filter((c) => c[0] === '/api/skills')
      expect(skillCalls.at(-1)?.[1]?.params).toMatchObject({ taskType: 'code-review' })
    })
  })

  it('forwards the riskLevel filter to the API query', async () => {
    routeGet()
    renderPage()
    await screen.findByText('Alpha')

    fireEvent.change(screen.getByLabelText('Risk level'), { target: { value: 'high' } })

    await waitFor(() => {
      const skillCalls = get.mock.calls.filter((c) => c[0] === '/api/skills')
      expect(skillCalls.at(-1)?.[1]?.params).toMatchObject({ riskLevel: 'high' })
    })
  })

  // 8.2 — live taxonomy populates dropdowns (active only)
  it('populates taxonomy dropdowns from the API and excludes inactive entries', async () => {
    routeGet()
    renderPage()
    await screen.findByText('Alpha')

    const taskTypeSelect = await screen.findByLabelText('Task type')
    await waitFor(() => {
      expect(within(taskTypeSelect).queryByText('code-review')).toBeInTheDocument()
    })
    // Inactive entry is excluded
    expect(within(taskTypeSelect).queryByText('legacy-inactive')).not.toBeInTheDocument()

    const languageSelect = screen.getByLabelText('Language')
    expect(within(languageSelect).queryByText('python')).toBeInTheDocument()
  })

  // 8.3 — taxonomy failure degrades gracefully (no crash, empty dropdown)
  it('renders without crashing and leaves taxonomy dropdowns empty on taxonomy failure', async () => {
    routeGet({ taxonomyRejects: true })
    renderPage()
    await screen.findByText('Alpha')

    const taskTypeSelect = screen.getByLabelText('Task type')
    // Only the default "All" option is present
    expect(within(taskTypeSelect).queryByText('code-review')).not.toBeInTheDocument()
    expect(taskTypeSelect).toBeInTheDocument()
  })

  // 8.4 — search debounce: one API call after typing settles
  it('debounces the search input (one query after the debounce window)', async () => {
    vi.useFakeTimers()
    try {
      routeGet()
      renderPage()

      const searchInput = screen.getByPlaceholderText('Search skills…')
      fireEvent.change(searchInput, { target: { value: 'k' } })
      fireEvent.change(searchInput, { target: { value: 'ku' } })
      fireEvent.change(searchInput, { target: { value: 'kub' } })

      // Before the debounce elapses, no /api/skills call carries the search term
      let searchCalls = get.mock.calls.filter(
        (c) => c[0] === '/api/skills' && c[1]?.params?.search,
      )
      expect(searchCalls).toHaveLength(0)

      vi.advanceTimersByTime(350)

      await vi.waitFor(() => {
        searchCalls = get.mock.calls.filter(
          (c) => c[0] === '/api/skills' && c[1]?.params?.search === 'kub',
        )
        expect(searchCalls.length).toBeGreaterThanOrEqual(1)
      })
    } finally {
      vi.useRealTimers()
    }
  })

  // 8.5 — URL state round-trip
  it('serializes and parses catalog filter state symmetrically', () => {
    const filters: FilterValues = {
      taskType: 'code-review',
      language: 'python',
      framework: '',
      tool: '',
      approvalState: 'Published',
      riskLevel: 'high',
      ownerTeam: '',
      visibility: 'organization',
    }
    const params = serializeCatalogState({ query: 'kubernetes', filters, page: 2 })
    const restored = parseCatalogState(params)

    expect(restored.query).toBe('kubernetes')
    expect(restored.page).toBe(2)
    expect(restored.filters).toMatchObject({
      taskType: 'code-review',
      language: 'python',
      approvalState: 'Published',
      riskLevel: 'high',
      visibility: 'organization',
    })
  })
})
