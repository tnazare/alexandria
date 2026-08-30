import type { SkillResponse } from '@/types/skill'

interface ChecklistItem {
  key: string
  label: string
  satisfied: boolean
}

/**
 * Computes the publish-readiness checklist for a skill. Exported for testing.
 *
 * The backend requires, to publish: at least one task_type tag, a visibility,
 * at least one supported tool, a risk level, non-empty applicability, and at
 * least one example.
 */
export function computePublishChecklist(skill: SkillResponse): ChecklistItem[] {
  const hasTaskType = skill.tags.some((t) => t.type === 'task_type')
  return [
    { key: 'task_type', label: 'Has a task type tag', satisfied: hasTaskType },
    {
      key: 'visibility',
      label: 'Visibility is set',
      satisfied: Boolean(skill.visibility),
    },
    {
      key: 'supportedTools',
      label: 'At least one supported tool',
      satisfied: skill.supportedTools.length > 0,
    },
    {
      key: 'riskLevel',
      label: 'Risk level is set',
      satisfied: Boolean(skill.riskLevel),
    },
    {
      key: 'applicability',
      label: 'Applicability is provided',
      satisfied: Boolean(skill.applicability && skill.applicability.trim().length > 0),
    },
    {
      key: 'examples',
      label: 'At least one example',
      satisfied: Boolean(skill.examples && skill.examples.trim().length > 0),
    },
  ]
}

interface PublishChecklistProps {
  skill: SkillResponse
}

/**
 * Displays the publish-readiness checklist with a green check / red cross per
 * required field.
 */
export function PublishChecklist({ skill }: PublishChecklistProps) {
  const items = computePublishChecklist(skill)
  const ready = items.every((i) => i.satisfied)

  return (
    <div className="rounded-md border border-neutral-200 p-4" data-testid="publish-checklist">
      <h3 className="mb-3 text-sm font-semibold text-neutral-800">
        Publish readiness
      </h3>
      <ul className="space-y-1.5">
        {items.map((item) => (
          <li
            key={item.key}
            className="flex items-center gap-2 text-sm"
            data-testid={`checklist-item-${item.key}`}
            data-satisfied={item.satisfied}
          >
            <span
              aria-hidden="true"
              className={item.satisfied ? 'text-green-600' : 'text-red-600'}
            >
              {item.satisfied ? '✓' : '✗'}
            </span>
            <span className={item.satisfied ? 'text-neutral-700' : 'text-neutral-500'}>
              {item.label}
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-3 text-xs font-medium">
        {ready ? (
          <span className="text-green-700">Ready to publish.</span>
        ) : (
          <span className="text-neutral-500">
            Complete the items above before publishing.
          </span>
        )}
      </p>
    </div>
  )
}
