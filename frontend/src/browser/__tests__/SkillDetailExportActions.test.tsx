import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router'
import type { ReactNode } from 'react'

// Mock the entire api/skills module so ExportActions and getSkill are controllable.
const mockGetSkill = vi.hoisted(() => vi.fn())
const mockExportSkill = vi.hoisted(() => vi.fn())

vi.mock('@/api/skills', () => ({
  getSkill: mockGetSkill,
  exportSkill: mockExportSkill,
}))

// Stub clipboard and URL APIs used by ExportActions.
const clipboardWrite = vi.fn()
vi.stubGlobal('navigator', { clipboard: { writeText: clipboardWrite } })
vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:fake'), revokeObjectURL: vi.fn() })

import { SkillDetailPage } from '../SkillDetailPage'

const SKILL_ID = 'skill-1'

type LifecycleStatus =
  | 'Draft' | 'Published' | 'Team_Approved' | 'Company_Approved'
  | 'Deprecated' | 'Archived' | 'Blocked'

function skillDetail(status: LifecycleStatus) {
  return {
    id: SKILL_ID,
    name: 'My Skill',
    description: 'A great skill',
    overview: null,
    instructions: 'Do the thing.',
    applicability: null,
    examples: [],
    limitations: null,
    riskLevel: 'low',
    lifecycleStatus: status,
    ownerTeam: 'Team Alpha',
    supportedTools: [],
    variants: [],
    reviewStatus: null,
    versionHistory: [],
    feedbackSummary: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/catalog/${SKILL_ID}`]}>
        <Routes>
          <Route path="/catalog/:skillId" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return render(<SkillDetailPage />, { wrapper })
}

describe('ExportActions on the browse SkillDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    clipboardWrite.mockResolvedValue(undefined)
  })

  it('shows copy and download buttons for a Published skill', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Published'))
    renderPage()

    expect(await screen.findByText('Copy generic prompt')).toBeInTheDocument()
    expect(screen.getByText('Copy for GitHub Copilot')).toBeInTheDocument()
    expect(screen.getByText('Export as AGENTS.md')).toBeInTheDocument()
    expect(screen.getByText('Export as Cursor rule')).toBeInTheDocument()
  })

  it('copy button calls exportSkill with source=copy and writes to clipboard', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Published'))
    mockExportSkill.mockResolvedValue('rendered artifact content')
    renderPage()

    fireEvent.click(await screen.findByText('Copy for GitHub Copilot'))

    await waitFor(() => {
      expect(mockExportSkill).toHaveBeenCalledWith(
        SKILL_ID,
        'github_copilot',
        expect.objectContaining({ source: 'copy' }),
      )
      expect(clipboardWrite).toHaveBeenCalledWith('rendered artifact content')
    })
    // Success flash shows "Copied!"
    expect(await screen.findByText('Copied!')).toBeInTheDocument()
  })

  it('download button calls exportSkill with source=download and triggers a file', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Published'))
    mockExportSkill.mockResolvedValue('agents md content')
    renderPage()

    fireEvent.click(await screen.findByText('Export as AGENTS.md'))

    await waitFor(() => {
      expect(mockExportSkill).toHaveBeenCalledWith(
        SKILL_ID,
        'agents_md',
        expect.objectContaining({ source: 'download' }),
      )
    })
    // triggerFileDownload creates a blob URL.
    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(await screen.findByText('Downloaded!')).toBeInTheDocument()
  })

  it('blocked skill hides export buttons and shows a blocked alert', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Blocked'))
    renderPage()

    expect(
      await screen.findByText('This skill is blocked and cannot be exported.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Copy generic prompt')).not.toBeInTheDocument()
    expect(screen.queryByText('Export as AGENTS.md')).not.toBeInTheDocument()
  })

  it('deprecated skill shows a warning but still renders export buttons', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Deprecated'))
    renderPage()

    expect(await screen.findByText(/This skill is deprecated/i)).toBeInTheDocument()
    // Export buttons still present.
    expect(screen.getByText('Copy generic prompt')).toBeInTheDocument()
  })

  it('shows an inline error message when export fails', async () => {
    mockGetSkill.mockResolvedValue(skillDetail('Published'))
    const err = { response: { data: { message: 'Export service unavailable' } } }
    mockExportSkill.mockRejectedValue(err)
    renderPage()

    fireEvent.click(await screen.findByText('Copy for Codex'))

    expect(
      await screen.findByText('Export service unavailable'),
    ).toBeInTheDocument()
  })
})
