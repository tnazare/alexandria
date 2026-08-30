import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { VisibilitySelector, VISIBILITY_OPTIONS } from '../VisibilitySelector'
import { VISIBILITIES } from '@/types/skill'

describe('VisibilitySelector', () => {
  it('renders all four visibility options with their labels', () => {
    render(<VisibilitySelector value="private" onChange={() => {}} />)
    const select = screen.getByLabelText('Visibility') as HTMLSelectElement
    const optionValues = Array.from(select.options).map((o) => o.value)
    expect(optionValues).toEqual([...VISIBILITIES])
    for (const v of VISIBILITIES) {
      expect(
        Array.from(select.options).some((o) => o.textContent === VISIBILITY_OPTIONS[v].label),
      ).toBe(true)
    }
  })

  it('shows the description for the selected value', () => {
    render(<VisibilitySelector value="team" onChange={() => {}} />)
    expect(screen.getByText(VISIBILITY_OPTIONS.team.description)).toBeInTheDocument()
  })

  it('calls onChange with the chosen visibility', () => {
    const onChange = vi.fn()
    render(<VisibilitySelector value="private" onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('Visibility'), {
      target: { value: 'organization' },
    })
    expect(onChange).toHaveBeenCalledWith('organization')
  })
})
