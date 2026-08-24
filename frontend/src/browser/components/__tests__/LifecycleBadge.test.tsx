import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import * as fc from 'fast-check'
import { LifecycleBadge } from '../LifecycleBadge'
import type { LifecycleStatus } from '@/api/skills'

/**
 * Property 13: Lifecycle badge mapping is exhaustive
 *
 * For every lifecycle status value in the set {Draft, Published, Team_Approved,
 * Company_Approved, Deprecated, Archived, Blocked}, the badge rendering function
 * SHALL produce a non-empty visual indicator (color or icon) that is distinct from
 * all other status values. No status value SHALL produce an undefined or fallback rendering.
 *
 * **Validates: Requirements 11.9**
 */

const ALL_LIFECYCLE_STATUSES: LifecycleStatus[] = [
  'Draft',
  'Published',
  'Team_Approved',
  'Company_Approved',
  'Deprecated',
  'Archived',
  'Blocked',
]

const lifecycleStatusArb = fc.constantFrom(...ALL_LIFECYCLE_STATUSES)

describe('LifecycleBadge - Property 13: Lifecycle badge mapping is exhaustive', () => {
  it('renders a non-empty element for every lifecycle status', () => {
    fc.assert(
      fc.property(lifecycleStatusArb, (status) => {
        const { container } = render(<LifecycleBadge status={status} />)
        const badge = container.querySelector('[data-testid="lifecycle-badge"]')

        // Badge element must exist
        expect(badge).not.toBeNull()

        // Badge must have non-empty text content
        expect(badge!.textContent!.trim().length).toBeGreaterThan(0)

        // Badge must have CSS classes applied (visual indicator)
        expect(badge!.className.length).toBeGreaterThan(0)
      }),
      { numRuns: 100 },
    )
  })

  it('produces distinct visual indicators for each status (no two statuses share identical styling)', () => {
    // Collect the className for each status
    const styleMap = new Map<LifecycleStatus, string>()

    for (const status of ALL_LIFECYCLE_STATUSES) {
      const { container } = render(<LifecycleBadge status={status} />)
      const badge = container.querySelector('[data-testid="lifecycle-badge"]')
      expect(badge).not.toBeNull()
      styleMap.set(status, badge!.className)
    }

    // Property: for any two distinct statuses, their styles must differ
    fc.assert(
      fc.property(
        fc.tuple(lifecycleStatusArb, lifecycleStatusArb).filter(
          ([a, b]) => a !== b,
        ),
        ([statusA, statusB]) => {
          const styleA = styleMap.get(statusA)!
          const styleB = styleMap.get(statusB)!
          expect(styleA).not.toEqual(styleB)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('every status in the defined set has a mapping (exhaustiveness check)', () => {
    // This property verifies the component handles ALL statuses without fallback
    fc.assert(
      fc.property(lifecycleStatusArb, (status) => {
        const { container } = render(<LifecycleBadge status={status} />)
        const badge = container.querySelector('[data-testid="lifecycle-badge"]')

        // Must render with the correct data-status attribute (proves no fallback)
        expect(badge).not.toBeNull()
        expect(badge!.getAttribute('data-status')).toEqual(status)

        // Must have status-specific color classes (not just generic/shared base classes)
        const className = badge!.className
        // Each status should have a bg-* color class that's specific to it
        expect(className).toMatch(/bg-\w+-\d+/)
        expect(className).toMatch(/text-\w+-\d+/)
      }),
      { numRuns: 100 },
    )
  })
})
