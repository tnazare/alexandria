import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import type { ReactNode } from 'react'

// Mock the axios client so no real network calls are made.
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
      <MemoryRouter initialEntries={['/catalog/skills/new']}>{children}</MemoryRouter>
    </QueryClientProvider>
  )
  return render(<SkillCreatePage />, { wrapper })
}

/**
 * Focused unit-style tests of the SkillForm client-side validation rules
 * (required fields + length limits), matching backend constraints:
 * name 3–200, description 1–2000, instructions 1–100000, owning team required.
 */
describe('SkillCreatePage form validation', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
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

  it('rejects a name shorter than 3 characters', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'ab' } })
    fireEvent.change(screen.getByLabelText(/Description/), { target: { value: 'valid' } })
    fireEvent.change(screen.getByLabelText(/Owning team/), { target: { value: 'team-1' } })
    fireEvent.change(screen.getByLabelText('Canonical instructions'), {
      target: { value: 'instructions' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    expect(await screen.findByText('Name must be 3–200 characters')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('enforces the name maxLength of 200 at the input level', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    const nameInput = screen.getByLabelText(/Name/) as HTMLInputElement
    // The browser caps input at maxLength; the field exposes that boundary.
    expect(nameInput.maxLength).toBe(200)
  })

  it('rejects an empty description', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Valid Name' } })
    fireEvent.change(screen.getByLabelText(/Owning team/), { target: { value: 'team-1' } })
    fireEvent.change(screen.getByLabelText('Canonical instructions'), {
      target: { value: 'instructions' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    expect(await screen.findByText('Description must be 1–2000 characters')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('rejects a missing owning team', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Valid Name' } })
    fireEvent.change(screen.getByLabelText(/Description/), { target: { value: 'valid' } })
    fireEvent.change(screen.getByLabelText('Canonical instructions'), {
      target: { value: 'instructions' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    expect(await screen.findByText('Select an owning team')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('rejects empty canonical instructions', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Valid Name' } })
    fireEvent.change(screen.getByLabelText(/Description/), { target: { value: 'valid' } })
    fireEvent.change(screen.getByLabelText(/Owning team/), { target: { value: 'team-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    expect(
      await screen.findByText('Instructions must be 1–100000 characters'),
    ).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('reports multiple field errors at once', async () => {
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    // Submit an empty form.
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    await waitFor(() => {
      expect(screen.getByText('Name must be 3–200 characters')).toBeInTheDocument()
    })
    expect(screen.getByText('Description must be 1–2000 characters')).toBeInTheDocument()
    expect(screen.getByText('Select an owning team')).toBeInTheDocument()
    expect(screen.getByText('Instructions must be 1–100000 characters')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('accepts input at the boundary (name length 3) and submits', async () => {
    post.mockResolvedValue({ data: { id: 'ok' } })
    renderPage()
    await screen.findByRole('option', { name: 'Platform' })

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'abc' } })
    fireEvent.change(screen.getByLabelText(/Description/), { target: { value: 'd' } })
    fireEvent.change(screen.getByLabelText(/Owning team/), { target: { value: 'team-1' } })
    fireEvent.change(screen.getByLabelText('Canonical instructions'), {
      target: { value: 'i' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/skills', {
        name: 'abc',
        description: 'd',
        ownerTeamId: 'team-1',
        canonicalInstructions: 'i',
      })
    })
  })
})
