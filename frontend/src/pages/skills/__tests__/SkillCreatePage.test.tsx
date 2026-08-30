import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import type { ReactNode } from 'react'

// Mock the axios client so hooks issue no real network calls.
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

import { SkillCreatePage } from '../SkillCreatePage'

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/catalog/skills/new']}>
        <Routes>
          <Route path="/catalog/skills/new" element={children} />
          <Route path="/catalog/skills/:skillId" element={<div>Detail Page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return render(<SkillCreatePage />, { wrapper })
}

describe('SkillCreatePage integration', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    // Teams list for the owner-team dropdown.
    get.mockResolvedValue({
      data: {
        content: [
          { id: 'team-1', name: 'Platform', slug: 'platform', description: null, memberCount: 1, createdAt: '' },
        ],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      },
    })
  })

  it('submits the form and navigates to the new skill detail page', async () => {
    post.mockResolvedValue({ data: { id: 'new-skill-id' } })
    renderPage()

    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Extract Method' } })
    fireEvent.change(screen.getByLabelText(/Description/), {
      target: { value: 'Helps extract methods safely' },
    })
    fireEvent.change(screen.getByLabelText(/Owning team/), { target: { value: 'team-1' } })
    fireEvent.change(screen.getByLabelText('Canonical instructions'), {
      target: { value: 'Do the refactor' },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/skills', {
        name: 'Extract Method',
        description: 'Helps extract methods safely',
        ownerTeamId: 'team-1',
        canonicalInstructions: 'Do the refactor',
      })
    })

    expect(await screen.findByText('Detail Page')).toBeInTheDocument()
  })

  it('shows validation errors and does not call the API for invalid input', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'ab' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    expect(await screen.findByText(/Name must be 3–200 characters/)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })
})
