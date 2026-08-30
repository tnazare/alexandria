import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { AxiosError } from 'axios'
import { listTeams } from '@/api/teams'
import { useCreateSkill } from '@/api/skillsCatalog'
import { MarkdownEditor } from '@/components/skill/MarkdownEditor'
import { Button, LoadingSpinner, ToastContainer, useToast } from '@/shared/components'

const NAME_MIN = 3
const NAME_MAX = 200
const DESC_MIN = 1
const DESC_MAX = 2000
const INSTR_MIN = 1
const INSTR_MAX = 100000

interface FieldErrors {
  name?: string
  description?: string
  ownerTeamId?: string
  canonicalInstructions?: string
}

function validate(
  name: string,
  description: string,
  ownerTeamId: string,
  instructions: string,
): FieldErrors {
  const errors: FieldErrors = {}
  if (name.trim().length < NAME_MIN || name.trim().length > NAME_MAX) {
    errors.name = `Name must be ${NAME_MIN}–${NAME_MAX} characters`
  }
  if (description.trim().length < DESC_MIN || description.trim().length > DESC_MAX) {
    errors.description = `Description must be ${DESC_MIN}–${DESC_MAX} characters`
  }
  if (!ownerTeamId) {
    errors.ownerTeamId = 'Select an owning team'
  }
  if (instructions.trim().length < INSTR_MIN || instructions.length > INSTR_MAX) {
    errors.canonicalInstructions = `Instructions must be ${INSTR_MIN}–${INSTR_MAX} characters`
  }
  return errors
}

/**
 * Page for creating a new skill. Route: /catalog/skills/new
 */
export function SkillCreatePage() {
  const navigate = useNavigate()
  const { toasts, toast, dismissToast } = useToast()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerTeamId, setOwnerTeamId] = useState('')
  const [instructions, setInstructions] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})

  const { data: teamsPage, isLoading: teamsLoading } = useQuery({
    queryKey: ['teams', 'all-for-select'],
    queryFn: () => listTeams(0, 100),
  })

  const createMutation = useCreateSkill()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const validationErrors = validate(name, description, ownerTeamId, instructions)
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) {
      return
    }
    createMutation.mutate(
      {
        name: name.trim(),
        description: description.trim(),
        ownerTeamId,
        canonicalInstructions: instructions,
      },
      {
        onSuccess: (skill) => {
          toast.success('Skill created')
          void navigate(`/catalog/skills/${skill.id}`)
        },
        onError: (error: unknown) => {
          if (error instanceof AxiosError && error.response?.status === 403) {
            toast.error('You are not a member of the selected team')
          } else {
            toast.error('Failed to create skill. Please try again.')
          }
        },
      },
    )
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      <div>
        <h1 className="text-2xl font-bold text-neutral-900">Create a skill</h1>
        <p className="mt-1 text-sm text-neutral-600">
          Start a new draft. You can add tags, tools, variants, and publish later.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-5">
        {/* Name */}
        <div>
          <label htmlFor="skill-name" className="mb-1 block text-sm font-medium text-neutral-700">
            Name <span className="text-red-500">*</span>
          </label>
          <input
            id="skill-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={NAME_MAX}
            className={`w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 ${
              errors.name ? 'border-red-300' : 'border-neutral-300'
            }`}
            aria-invalid={!!errors.name}
          />
          {errors.name && <p className="mt-1 text-sm text-red-600">{errors.name}</p>}
        </div>

        {/* Description */}
        <div>
          <label htmlFor="skill-description" className="mb-1 block text-sm font-medium text-neutral-700">
            Description <span className="text-red-500">*</span>
          </label>
          <textarea
            id="skill-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={DESC_MAX}
            rows={3}
            className={`w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 ${
              errors.description ? 'border-red-300' : 'border-neutral-300'
            }`}
            aria-invalid={!!errors.description}
          />
          {errors.description && (
            <p className="mt-1 text-sm text-red-600">{errors.description}</p>
          )}
        </div>

        {/* Owner team */}
        <div>
          <label htmlFor="skill-team" className="mb-1 block text-sm font-medium text-neutral-700">
            Owning team <span className="text-red-500">*</span>
          </label>
          {teamsLoading ? (
            <LoadingSpinner />
          ) : (
            <select
              id="skill-team"
              value={ownerTeamId}
              onChange={(e) => setOwnerTeamId(e.target.value)}
              className={`w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 ${
                errors.ownerTeamId ? 'border-red-300' : 'border-neutral-300'
              }`}
              aria-invalid={!!errors.ownerTeamId}
            >
              <option value="">Select a team…</option>
              {(teamsPage?.content ?? []).map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          )}
          {errors.ownerTeamId && (
            <p className="mt-1 text-sm text-red-600">{errors.ownerTeamId}</p>
          )}
        </div>

        {/* Canonical instructions */}
        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">
            Canonical instructions <span className="text-red-500">*</span>
          </label>
          <MarkdownEditor
            id="skill-instructions"
            ariaLabel="Canonical instructions"
            value={instructions}
            onChange={setInstructions}
            rows={14}
          />
          {errors.canonicalInstructions && (
            <p className="mt-1 text-sm text-red-600">{errors.canonicalInstructions}</p>
          )}
        </div>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={() => navigate('/catalog')}>
            Cancel
          </Button>
          <Button type="submit" loading={createMutation.isPending}>
            Create skill
          </Button>
        </div>
      </form>
    </div>
  )
}
