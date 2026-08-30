import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import type { ReactNode } from 'react'
import type { PageResponse } from '@/shared/types'
import type { SkillResponse, SkillVersionResponse } from '@/types/skill'

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

import { SkillVersionsPage } from '../SkillVersionsPage'
import { SkillVersionDetailPage } from '../SkillVersionDetailPage'

const SKILL_ID = 'skill-1'

function skill(): SkillResponse {
  return {
    id: SKILL_ID,
    name: 'Versioned Skill',
    slug: 'versioned-skill',
    description: 'desc',
    ownerTeamId: 't1',
    ownerTeamName: 'Team',
    lifecycleStatus: 'Published',
    visibility: 'organization',
    riskLevel: 'low',
    currentVersionNumber: 2,
    canonicalInstructions: 'canonical',
    applicability: null,
    limitations: null,
    whenToUse: null,
    examples: null,
    changelog: null,
    tags: [],
    supportedTools: [],
    variants: [],
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    publishedAt: '2026-01-02T00:00:00Z',
  }
}

function version(n: number, changelog: string): SkillVersionResponse {
  return {
    id: `v${n}`,
    versionNumber: n,
    canonicalInstructions: `instructions for v${n}`,
    applicability: null,
    limitations: null,
    whenToUse: null,
    examples: null,
    changelog,
    variants: [],
    createdAt: '2026-01-01T00:00:00Z',
    publishedAt: n === 2 ? '2026-01-02T00:00:00Z' : null,
  }
}

const versionsPage: PageResponse<SkillVersionResponse> = {
  content: [version(2, 'Second version'), version(1, 'Initial draft')],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
}

function routeGet() {
  get.mockImplementation((url: string) => {
    if (url === `/api/skills/${SKILL_ID}`) return Promise.resolve({ data: skill() })
    if (url === `/api/skills/${SKILL_ID}/versions`) return Promise.resolve({ data: versionsPage })
    if (url === `/api/skills/${SKILL_ID}/versions/2`) return Promise.resolve({ data: version(2, 'Second version') })
    if (url === `/api/skills/${SKILL_ID}/versions/1`) return Promise.resolve({ data: version(1, 'Initial draft') })
    return Promise.resolve({ data: {} })
  })
}

function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/catalog/skills/${SKILL_ID}/versions`]}>
        <Routes>
          <Route path="/catalog/skills/:skillId/versions" element={<SkillVersionsPage />} />
          <Route
            path="/catalog/skills/:skillId/versions/:versionNumber"
            element={<SkillVersionDetailPage />}
          />
        </Routes>
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  )
  return render(<></>, { wrapper })
}

describe('Version history navigation', () => {
  beforeEach(() => {
    get.mockReset()
    routeGet()
  })

  it('lists versions in the timeline', async () => {
    renderApp()
    const items = await screen.findAllByTestId('version-timeline-item')
    expect(items).toHaveLength(2)
    expect(screen.getByText('Second version')).toBeInTheDocument()
    expect(screen.getByText('Initial draft')).toBeInTheDocument()
    // Descending order: v2 link appears before v1.
    expect(screen.getByRole('link', { name: 'v2' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'v1' })).toBeInTheDocument()
  })

  it('navigates to a version detail page when a version link is clicked', async () => {
    renderApp()
    const v1Link = await screen.findByRole('link', { name: 'v1' })

    fireEvent.click(v1Link)

    // The version detail page renders the selected version's heading and content.
    expect(await screen.findByRole('heading', { name: 'Version 1' })).toBeInTheDocument()
    expect(screen.getByText('instructions for v1')).toBeInTheDocument()
    // And a back link to the version history.
    expect(
      screen.getByRole('link', { name: '← Back to version history' }),
    ).toBeInTheDocument()
  })
})
