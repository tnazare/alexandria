import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { TagPicker, activeEntriesById } from '../TagPicker'
import type { TaxonomyGroupResponse } from '@/types/taxonomy'

const groups: TaxonomyGroupResponse[] = [
  {
    tagType: 'task_type',
    entries: [
      { id: 'a', tagType: 'task_type', value: 'refactoring', description: null, active: true, createdAt: '' },
      { id: 'b', tagType: 'task_type', value: 'old-thing', description: null, active: false, createdAt: '' },
    ],
  },
  {
    tagType: 'language',
    entries: [
      { id: 'c', tagType: 'language', value: 'Java', description: null, active: true, createdAt: '' },
    ],
  },
]

describe('TagPicker', () => {
  it('excludes inactive entries from the selectable options', () => {
    render(<TagPicker groups={groups} selectedIds={[]} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'refactoring' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Java' })).toBeInTheDocument()
    // Inactive entry must not be offered.
    expect(screen.queryByRole('button', { name: 'old-thing' })).toBeNull()
  })

  it('activeEntriesById only includes active entries', () => {
    const map = activeEntriesById(groups)
    expect(map.has('a')).toBe(true)
    expect(map.has('c')).toBe(true)
    expect(map.has('b')).toBe(false)
  })

  it('renders selected active tags as chips', () => {
    render(<TagPicker groups={groups} selectedIds={['a']} onChange={() => {}} />)
    const chips = screen.getAllByTestId('tag-chip')
    expect(chips).toHaveLength(1)
    expect(chips[0]?.textContent).toContain('refactoring')
  })

  it('toggles selection when an option is clicked', () => {
    const onChange = vi.fn()
    render(<TagPicker groups={groups} selectedIds={[]} onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: 'Java' }))
    expect(onChange).toHaveBeenCalledWith(['c'])
  })
})
