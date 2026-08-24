import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import {
  serializeCatalogState,
  parseCatalogState,
  type CatalogUrlState,
} from '../urlState'
import type { FilterValues } from '@/browser/components/FilterPanel'

/**
 * Property 14: URL state round-trip for search and filters
 *
 * For any combination of a search query string and a set of filter selections,
 * serializing the state to URL query parameters and then parsing those parameters
 * back SHALL produce an equivalent state object. No information is lost in the round-trip.
 *
 * **Validates: Requirements 11.12**
 */
describe('Property 14: URL state round-trip for search and filters', () => {
  /**
   * Arbitrary for filter values: either empty string or a non-empty trimmed string.
   */
  const filterValueArb = fc.oneof(
    fc.constant(''),
    fc.string({ minLength: 1, maxLength: 50 }).filter((s) => s.trim().length > 0),
  )

  const filterValuesArb: fc.Arbitrary<FilterValues> = fc.record({
    taskType: filterValueArb,
    language: filterValueArb,
    framework: filterValueArb,
    tool: filterValueArb,
    approvalState: filterValueArb,
    riskLevel: filterValueArb,
    ownerTeam: filterValueArb,
    visibility: filterValueArb,
  })

  const catalogUrlStateArb: fc.Arbitrary<CatalogUrlState> = fc.record({
    query: filterValueArb,
    filters: filterValuesArb,
    page: fc.nat({ max: 1000 }),
  })

  it('serialize → deserialize produces equivalent state (round-trip)', () => {
    fc.assert(
      fc.property(catalogUrlStateArb, (state) => {
        const serialized = serializeCatalogState(state)
        const deserialized = parseCatalogState(serialized)

        // Normalize: empty strings in original state should match empty strings after round-trip
        const normalizedOriginal: CatalogUrlState = {
          query: state.query || '',
          filters: {
            taskType: state.filters.taskType || '',
            language: state.filters.language || '',
            framework: state.filters.framework || '',
            tool: state.filters.tool || '',
            approvalState: state.filters.approvalState || '',
            riskLevel: state.filters.riskLevel || '',
            ownerTeam: state.filters.ownerTeam || '',
            visibility: state.filters.visibility || '',
          },
          page: state.page,
        }

        expect(deserialized).toEqual(normalizedOriginal)
      }),
      { numRuns: 100 },
    )
  })

  it('no information is lost: non-empty values survive round-trip', () => {
    fc.assert(
      fc.property(catalogUrlStateArb, (state) => {
        const serialized = serializeCatalogState(state)
        const deserialized = parseCatalogState(serialized)

        // Every non-empty field in input must appear identically in output
        if (state.query) {
          expect(deserialized.query).toBe(state.query)
        }

        for (const key of Object.keys(state.filters) as Array<keyof FilterValues>) {
          if (state.filters[key]) {
            expect(deserialized.filters[key]).toBe(state.filters[key])
          }
        }

        // Page is always preserved
        expect(deserialized.page).toBe(state.page)
      }),
      { numRuns: 100 },
    )
  })

  it('empty state serializes to empty params and deserializes back to empty', () => {
    const emptyState: CatalogUrlState = {
      query: '',
      filters: {
        taskType: '',
        language: '',
        framework: '',
        tool: '',
        approvalState: '',
        riskLevel: '',
        ownerTeam: '',
        visibility: '',
      },
      page: 0,
    }

    const serialized = serializeCatalogState(emptyState)
    expect(serialized.toString()).toBe('')

    const deserialized = parseCatalogState(serialized)
    expect(deserialized).toEqual(emptyState)
  })
})
