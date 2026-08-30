import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import * as fc from 'fast-check'
import { PublishChecklist, computePublishChecklist } from '../PublishChecklist'
import type { SkillResponse } from '@/types/skill'

function baseSkill(overrides: Partial<SkillResponse> = {}): SkillResponse {
  return {
    id: 's1',
    name: 'Test',
    slug: 'test',
    description: 'desc',
    ownerTeamId: 't1',
    ownerTeamName: 'Team',
    lifecycleStatus: 'Draft',
    visibility: 'private',
    riskLevel: null,
    currentVersionNumber: 1,
    canonicalInstructions: 'do it',
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
    publishedAt: null,
    ...overrides,
  }
}

const fullyReady = baseSkill({
  riskLevel: 'low',
  applicability: 'applies here',
  examples: 'an example',
  supportedTools: ['cursor'],
  tags: [{ id: 'g1', type: 'task_type', value: 'refactoring' }],
})

describe('PublishChecklist', () => {
  it('marks all items satisfied for a fully-ready skill', () => {
    const items = computePublishChecklist(fullyReady)
    expect(items.every((i) => i.satisfied)).toBe(true)
  })

  it('flags each individually missing field', () => {
    expect(
      computePublishChecklist(baseSkill({ ...fullyReady, riskLevel: null }))
        .find((i) => i.key === 'riskLevel')!.satisfied,
    ).toBe(false)
    expect(
      computePublishChecklist(baseSkill({ ...fullyReady, examples: null }))
        .find((i) => i.key === 'examples')!.satisfied,
    ).toBe(false)
    expect(
      computePublishChecklist(baseSkill({ ...fullyReady, supportedTools: [] }))
        .find((i) => i.key === 'supportedTools')!.satisfied,
    ).toBe(false)
    expect(
      computePublishChecklist(baseSkill({ ...fullyReady, tags: [] }))
        .find((i) => i.key === 'task_type')!.satisfied,
    ).toBe(false)
  })

  it('a non-task_type tag does not satisfy the task type requirement', () => {
    const items = computePublishChecklist(
      baseSkill({ ...fullyReady, tags: [{ id: 'g', type: 'language', value: 'Java' }] }),
    )
    expect(items.find((i) => i.key === 'task_type')!.satisfied).toBe(false)
  })

  it('renders a ready message when all items pass (property over random completeness)', () => {
    fc.assert(
      fc.property(
        fc.record({
          risk: fc.boolean(),
          applic: fc.boolean(),
          examples: fc.boolean(),
          tools: fc.boolean(),
          taskTag: fc.boolean(),
        }),
        (flags) => {
          const skill = baseSkill({
            riskLevel: flags.risk ? 'low' : null,
            applicability: flags.applic ? 'x' : null,
            examples: flags.examples ? 'x' : null,
            supportedTools: flags.tools ? ['cursor'] : [],
            tags: flags.taskTag ? [{ id: 'g', type: 'task_type', value: 'r' }] : [],
          })
          const { unmount } = render(<PublishChecklist skill={skill} />)
          const allPass =
            flags.risk && flags.applic && flags.examples && flags.tools && flags.taskTag
          if (allPass) {
            expect(screen.getByText('Ready to publish.')).toBeInTheDocument()
          } else {
            expect(
              screen.getByText('Complete the items above before publishing.'),
            ).toBeInTheDocument()
          }
          unmount()
        },
      ),
      { numRuns: 40 },
    )
  })
})
