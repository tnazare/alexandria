import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import * as fc from 'fast-check'
import { LifecycleStatusBadge, LIFECYCLE_BADGE_CLASSES } from '../LifecycleStatusBadge'
import type { LifecycleStatus } from '@/types/skill'

const ALL: LifecycleStatus[] = [
  'Draft',
  'Published',
  'Team_Approved',
  'Company_Approved',
  'Deprecated',
  'Archived',
  'Blocked',
]

describe('LifecycleStatusBadge', () => {
  it('renders the correct color class for every status', () => {
    fc.assert(
      fc.property(fc.constantFrom(...ALL), (status) => {
        const { container } = render(<LifecycleStatusBadge status={status} />)
        const badge = container.querySelector('[data-testid="lifecycle-status-badge"]')
        expect(badge).not.toBeNull()
        expect(badge!.getAttribute('data-status')).toEqual(status)
        // Contains the mapped color classes for this status.
        for (const cls of LIFECYCLE_BADGE_CLASSES[status].split(' ')) {
          expect(badge!.className).toContain(cls)
        }
      }),
      { numRuns: 50 },
    )
  })

  it('maps each status to a distinct color set', () => {
    const classes = ALL.map((s) => LIFECYCLE_BADGE_CLASSES[s])
    expect(new Set(classes).size).toBe(ALL.length)
  })
})
