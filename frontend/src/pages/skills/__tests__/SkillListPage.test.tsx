import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import type { ReactNode } from 'react'
import type { PageResponse } from '@/shared/types'
import type { SkillSummaryResponse } from '@/types/skill'

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

/**
 * Routes both /api/skills and /api/teams through the mocked axios get, returning
 * the given skills page for skill queries and empty teams for the filter dropdown.
 */
function routeGet(skills: PageResponse<SkillSummaryResponse>) {
  get.mockImplementation((url: string) => {
    if (url === '/api/teams') return Promise.resolve(EMPTY_TEAMS)
    if (url === '/api/skills') return Promise.resolve({ data: skills })
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

describe('SkillListPage integration', () => {
  beforeEach(() => {
    get.mockReset()
  })

  it('renders skill summary cards from the API', async () => {
    routeGet(pageOf([summary('Alpha'), summary('Beta')]))
    renderPage()

    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
    expect(screen.getByText('2 skills found')).toBeInTheDocument()
  })

  it('passes the status filter to the API query', async () => {
    routeGet(pageOf([summary('Alpha')]))
    renderPage()
    await screen.findByText('Alpha')

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'Draft' } })

    await waitFor(() => {
      const skillCalls = get.mock.calls.filter((c) => c[0] === '/api/skills')
      const lastParams = skillCalls.at(-1)?.[1]?.params
      expect(lastParams).toMatchObject({ status: 'Draft' })
    })
  })
})
