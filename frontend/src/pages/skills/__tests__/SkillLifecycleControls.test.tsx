import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import type { ReactNode } from 'react'
import { AxiosError } from 'axios'
import type { LifecycleStatus, SkillResponse } from '@/types/skill'

const get = vi.fn()
const post = vi.fn()
vi.mock('@/api/client', () => ({
  apiClient: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}))

// Mutable role controlled per-test; useAuth reads it lazily.
const authState = vi.hoisted(() => ({ userRole: 'Catalog_Admin' as string | null }))
vi.mock('@/auth', () => ({
  useAuth: () => ({ userRole: authState.userRole }),
}))

import { SkillDetailPage } from '../SkillDetailPage'

const SKILL_ID = 'skill-1'

function skill(
  status: LifecycleStatus,
  deprecationReason: string | null = null,
): SkillResponse {
  return {
    id: SKILL_ID,
    name: 'Governed Skill',
    slug: 'governed-skill',
    description: 'desc',
    ownerTeamId: 't1',
    ownerTeamName: 'Team',
    lifecycleStatus: status,
    deprecationReason,
    visibility: 'organization',
    riskLevel: 'low',
    currentVersionNumber: 1,
    canonicalInstructions: 'canonical instructions',
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
    updatedAt: '2026-01-01T00:00:00Z',
    publishedAt: '2026-01-01T00:00:00Z',
  }
}

function routeGet(current: SkillResponse) {
  get.mockImplementation((url: string) => {
    if (url === `/api/skills/${SKILL_ID}`) {
      return Promise.resolve({ data: current })
    }
    return Promise.resolve({ data: {} })
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/catalog/skills/${SKILL_ID}`]}>
        <Routes>
          <Route path="/catalog/skills/:skillId" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return render(<SkillDetailPage />, { wrapper })
}

function axios409(): AxiosError {
  const err = new AxiosError('conflict')
  err.response = { status: 409, data: {}, statusText: '', headers: {}, config: {} as never }
  return err
}

describe('Skill lifecycle governance controls', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    authState.userRole = 'Catalog_Admin'
    // Block/Archive prompt for confirmation; auto-confirm in tests.
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('hides the governance panel for non-admin roles', async () => {
    authState.userRole = 'Author'
    routeGet(skill('Published'))
    renderPage()

    expect(await screen.findByText('Governed Skill')).toBeInTheDocument()
    expect(screen.queryByText('Governance:')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deprecate' })).not.toBeInTheDocument()
  })

  it('shows deprecate/block/archive (not restore) for a Published skill to an admin', async () => {
    routeGet(skill('Published'))
    renderPage()

    expect(await screen.findByText('Governance:')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deprecate' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Block' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Archive' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Restore' })).not.toBeInTheDocument()
  })

  it('shows only Restore and a blocked indicator for a Blocked skill', async () => {
    routeGet(skill('Blocked'))
    renderPage()

    expect(await screen.findByText('This skill is blocked.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deprecate' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Block' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
  })

  it('renders the deprecation banner with the reason for a Deprecated skill', async () => {
    routeGet(skill('Deprecated', 'Use skill X instead'))
    renderPage()

    expect(await screen.findByText('This skill is deprecated.')).toBeInTheDocument()
    expect(screen.getByText('Use skill X instead')).toBeInTheDocument()
    // Deprecated allows restore/block/archive but not a second deprecate.
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deprecate' })).not.toBeInTheDocument()
  })

  it('submits the deprecation reason via the form', async () => {
    routeGet(skill('Published'))
    post.mockResolvedValue({ data: skill('Deprecated', 'No longer maintained') })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Deprecate' }))
    const textarea = screen.getByLabelText('Deprecation reason (optional)')
    fireEvent.change(textarea, { target: { value: 'No longer maintained' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deprecation' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(`/api/skills/${SKILL_ID}/deprecate`, {
        reason: 'No longer maintained',
      })
    })
  })

  it('surfaces a 409 conflict when a transition is rejected', async () => {
    routeGet(skill('Published'))
    post.mockRejectedValue(axios409())
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Archive' }))

    expect(
      await screen.findByText('Cannot archive the skill from its current status'),
    ).toBeInTheDocument()
  })
})
