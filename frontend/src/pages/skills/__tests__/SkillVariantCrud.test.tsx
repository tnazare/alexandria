import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import type { ReactNode } from 'react'
import type { SkillResponse, VariantSummary } from '@/types/skill'

const get = vi.fn()
const post = vi.fn()
const put = vi.fn()
const del = vi.fn()
vi.mock('@/api/client', () => ({
  apiClient: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    put: (...args: unknown[]) => put(...args),
    patch: vi.fn(),
    delete: (...args: unknown[]) => del(...args),
  },
}))

vi.mock('@/auth', () => ({
  useAuth: () => ({ userRole: 'Author' }),
}))

import { SkillDetailPage } from '../SkillDetailPage'

const SKILL_ID = 'skill-1'

function variant(tool: string, instructions: string): VariantSummary {
  return {
    id: `var-${tool}`,
    tool,
    instructions,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

function skill(variants: VariantSummary[]): SkillResponse {
  return {
    id: SKILL_ID,
    name: 'Variant Host',
    slug: 'variant-host',
    description: 'desc',
    ownerTeamId: 't1',
    ownerTeamName: 'Team',
    lifecycleStatus: 'Published', // Published so the only "Delete" button is the variant's.
    deprecationReason: null,
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
    variants,
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    publishedAt: '2026-01-01T00:00:00Z',
  }
}

let currentVariants: VariantSummary[] = []

function routeGet() {
  get.mockImplementation((url: string) => {
    if (url === `/api/skills/${SKILL_ID}`) {
      return Promise.resolve({ data: skill(currentVariants) })
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

describe('Variant CRUD on the skill detail page', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    put.mockReset()
    del.mockReset()
    currentVariants = []
    routeGet()
  })

  it('creates a variant for a tool on the current version', async () => {
    post.mockResolvedValue({
      data: variant('cursor', 'cursor instructions'),
    })

    renderPage()
    await screen.findByRole('heading', { name: 'Variant Host' })

    // Open the variant editor.
    fireEvent.click(screen.getByRole('button', { name: 'Manage variants' }))

    // Fill in the new-variant form (default tool is the first available: 'generic').
    fireEvent.change(screen.getByLabelText('Tool'), { target: { value: 'cursor' } })
    fireEvent.change(screen.getByLabelText('New variant instructions'), {
      target: { value: 'cursor instructions' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add variant' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(
        `/api/skills/${SKILL_ID}/versions/1/variants`,
        { tool: 'cursor', instructions: 'cursor instructions' },
      )
    })
  })

  it('updates an existing variant', async () => {
    currentVariants = [variant('cursor', 'old instructions')]
    routeGet()
    put.mockResolvedValue({ data: variant('cursor', 'new instructions') })

    renderPage()
    await screen.findByRole('heading', { name: 'Variant Host' })
    fireEvent.click(screen.getByRole('button', { name: 'Manage variants' }))

    // Edit the existing cursor variant instructions.
    const textarea = screen.getByLabelText('cursor instructions') // ariaLabel = `${tool} instructions`
    fireEvent.change(textarea, { target: { value: 'new instructions' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith(
        `/api/skills/${SKILL_ID}/versions/1/variants/cursor`,
        { tool: 'cursor', instructions: 'new instructions' },
      )
    })
  })

  it('deletes an existing variant', async () => {
    currentVariants = [variant('cursor', 'some instructions')]
    routeGet()
    del.mockResolvedValue({ data: undefined })

    renderPage()
    await screen.findByRole('heading', { name: 'Variant Host' })
    fireEvent.click(screen.getByRole('button', { name: 'Manage variants' }))

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    await waitFor(() => {
      expect(del).toHaveBeenCalledWith(
        `/api/skills/${SKILL_ID}/versions/1/variants/cursor`,
      )
    })
  })

  it('does not offer already-used tools when creating a new variant', async () => {
    currentVariants = [variant('cursor', 'x')]
    routeGet()

    renderPage()
    await screen.findByRole('heading', { name: 'Variant Host' })
    fireEvent.click(screen.getByRole('button', { name: 'Manage variants' }))

    const toolSelect = screen.getByLabelText('Tool') as HTMLSelectElement
    const options = within(toolSelect).getAllByRole('option').map((o) => (o as HTMLOptionElement).value)
    expect(options).not.toContain('cursor')
    expect(options).toContain('generic')
  })
})
