import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { AxiosError } from 'axios'
import { useSkill, useUpdateSkill } from '@/api/skillsCatalog'
import { useTaxonomy } from '@/api/taxonomy'
import { MarkdownEditor } from '@/components/skill/MarkdownEditor'
import { TagPicker } from '@/components/skill/TagPicker'
import { VisibilitySelector } from '@/components/skill/VisibilitySelector'
import { PublishChecklist } from '@/components/skill/PublishChecklist'
import { Button, LoadingSpinner, ToastContainer, useToast } from '@/shared/components'
import { RISK_LEVELS, SUPPORTED_TOOLS } from '@/types/skill'
import type { RiskLevel, UpdateSkillRequest, Visibility } from '@/types/skill'

/**
 * Page for editing an existing skill. Route: /catalog/skills/:skillId/edit
 *
 * <p>For Draft skills, edits are saved in place and a publish checklist is
 * shown. For non-Draft skills, a changelog is required and saving creates a
 * new version (server-side).</p>
 */
export function SkillEditPage() {
  const { skillId } = useParams<{ skillId: string }>()
  const navigate = useNavigate()
  const { toasts, toast, dismissToast } = useToast()

  const { data: skill, isLoading, isError } = useSkill(skillId)
  const { data: taxonomy } = useTaxonomy()
  const updateMutation = useUpdateSkill(skillId ?? '')

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [instructions, setInstructions] = useState('')
  const [applicability, setApplicability] = useState('')
  const [limitations, setLimitations] = useState('')
  const [whenToUse, setWhenToUse] = useState('')
  const [examples, setExamples] = useState('')
  const [changelog, setChangelog] = useState('')
  const [visibility, setVisibility] = useState<Visibility>('private')
  const [riskLevel, setRiskLevel] = useState<RiskLevel | ''>('')
  const [tagIds, setTagIds] = useState<string[]>([])
  const [supportedTools, setSupportedTools] = useState<string[]>([])
  const [changelogError, setChangelogError] = useState<string | null>(null)

  // Pre-fill form when the skill loads.
  useEffect(() => {
    if (!skill) return
    setName(skill.name)
    setDescription(skill.description)
    setInstructions(skill.canonicalInstructions ?? '')
    setApplicability(skill.applicability ?? '')
    setLimitations(skill.limitations ?? '')
    setWhenToUse(skill.whenToUse ?? '')
    setExamples(skill.examples ?? '')
    setVisibility(skill.visibility)
    setRiskLevel(skill.riskLevel ?? '')
    setTagIds(skill.tags.map((t) => t.id))
    setSupportedTools(skill.supportedTools)
  }, [skill])

  const isDraft = skill?.lifecycleStatus === 'Draft'
  const changelogRequired = skill != null && !isDraft

  // A live projection of the current form state for the publish checklist.
  // Tags are resolved from the selected taxonomy entries so newly-added tags
  // (not yet persisted on the skill) are reflected in the checklist.
  const projectedSkill = useMemo(() => {
    if (!skill) return null
    const allEntries = (taxonomy ?? []).flatMap((g) => g.entries)
    const selectedTags = tagIds
      .map((id) => allEntries.find((e) => e.id === id))
      .filter((e): e is NonNullable<typeof e> => e != null)
      .map((e) => ({ id: e.id, type: e.tagType, value: e.value }))
    return {
      ...skill,
      applicability,
      examples,
      visibility,
      riskLevel: (riskLevel || null) as RiskLevel | null,
      supportedTools,
      tags: selectedTags,
    }
  }, [skill, taxonomy, applicability, examples, visibility, riskLevel, supportedTools, tagIds])

  function toggleTool(tool: string) {
    setSupportedTools((prev) =>
      prev.includes(tool) ? prev.filter((t) => t !== tool) : [...prev, tool],
    )
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (changelogRequired && changelog.trim().length === 0) {
      setChangelogError('A changelog is required when editing a published skill')
      return
    }
    setChangelogError(null)

    const request: UpdateSkillRequest = {
      name: name.trim(),
      description: description.trim(),
      canonicalInstructions: instructions,
      applicability: applicability || undefined,
      limitations: limitations || undefined,
      whenToUse: whenToUse || undefined,
      examples: examples || undefined,
      changelog: changelog.trim() || undefined,
      tagIds,
      supportedTools,
      riskLevel: riskLevel || null,
    }

    updateMutation.mutate(request, {
      onSuccess: () => {
        toast.success('Skill saved')
        void navigate(`/catalog/skills/${skillId}`)
      },
      onError: (error: unknown) => {
        if (error instanceof AxiosError) {
          const status = error.response?.status
          if (status === 403) {
            toast.error('You do not have permission to edit this skill')
            return
          }
          if (status === 422) {
            toast.error('One or more tags are invalid or inactive')
            return
          }
          if (status === 409) {
            toast.error('This skill cannot be edited in its current status')
            return
          }
        }
        toast.error('Failed to save the skill. Please try again.')
      },
    })
  }

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }
  if (isError || !skill) {
    return (
      <div className="py-12 text-center text-neutral-600">Skill not found.</div>
    )
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      <div>
        <h1 className="text-2xl font-bold text-neutral-900">Edit skill</h1>
        <p className="mt-1 text-sm text-neutral-600">
          {isDraft
            ? 'Editing a draft updates the current version in place.'
            : 'Saving a published skill creates a new version and returns it to Draft.'}
        </p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <form onSubmit={handleSubmit} className="space-y-5 lg:col-span-2">
          <div>
            <label htmlFor="edit-name" className="mb-1 block text-sm font-medium text-neutral-700">
              Name
            </label>
            <input
              id="edit-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
          </div>

          <div>
            <label htmlFor="edit-description" className="mb-1 block text-sm font-medium text-neutral-700">
              Description
            </label>
            <textarea
              id="edit-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              Canonical instructions
            </label>
            <MarkdownEditor
              id="edit-instructions"
              ariaLabel="Canonical instructions"
              value={instructions}
              onChange={setInstructions}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              Applicability
            </label>
            <MarkdownEditor
              id="edit-applicability"
              ariaLabel="Applicability"
              value={applicability}
              onChange={setApplicability}
              rows={5}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              When to use
            </label>
            <MarkdownEditor
              id="edit-when-to-use"
              ariaLabel="When to use"
              value={whenToUse}
              onChange={setWhenToUse}
              rows={5}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              Examples
            </label>
            <MarkdownEditor
              id="edit-examples"
              ariaLabel="Examples"
              value={examples}
              onChange={setExamples}
              rows={6}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              Limitations
            </label>
            <MarkdownEditor
              id="edit-limitations"
              ariaLabel="Limitations"
              value={limitations}
              onChange={setLimitations}
              rows={5}
            />
          </div>

          {/* Changelog */}
          <div>
            <label htmlFor="edit-changelog" className="mb-1 block text-sm font-medium text-neutral-700">
              Changelog{' '}
              {changelogRequired ? (
                <span className="text-red-500">*</span>
              ) : (
                <span className="text-neutral-400">(optional for drafts)</span>
              )}
            </label>
            <textarea
              id="edit-changelog"
              value={changelog}
              onChange={(e) => setChangelog(e.target.value)}
              rows={2}
              maxLength={2000}
              className={`w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 ${
                changelogError ? 'border-red-300' : 'border-neutral-300'
              }`}
              aria-invalid={!!changelogError}
            />
            {changelogError && (
              <p className="mt-1 text-sm text-red-600">{changelogError}</p>
            )}
          </div>

          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              onClick={() => navigate(`/catalog/skills/${skillId}`)}
            >
              Cancel
            </Button>
            <Button type="submit" loading={updateMutation.isPending}>
              Save
            </Button>
          </div>
        </form>

        {/* Sidebar: metadata + publish checklist */}
        <aside className="space-y-5">
          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">
              Visibility
            </label>
            <VisibilitySelector value={visibility} onChange={setVisibility} />
          </div>

          <div>
            <label htmlFor="edit-risk" className="mb-1 block text-sm font-medium text-neutral-700">
              Risk level
            </label>
            <select
              id="edit-risk"
              value={riskLevel}
              onChange={(e) => setRiskLevel(e.target.value as RiskLevel | '')}
              className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="">Not set</option>
              {RISK_LEVELS.map((level) => (
                <option key={level} value={level}>
                  {level}
                </option>
              ))}
            </select>
          </div>

          <div>
            <span className="mb-1 block text-sm font-medium text-neutral-700">
              Supported tools
            </span>
            <div className="space-y-1">
              {SUPPORTED_TOOLS.map((tool) => (
                <label key={tool} className="flex items-center gap-2 text-sm text-neutral-700">
                  <input
                    type="checkbox"
                    checked={supportedTools.includes(tool)}
                    onChange={() => toggleTool(tool)}
                  />
                  {tool}
                </label>
              ))}
            </div>
          </div>

          <div>
            <span className="mb-1 block text-sm font-medium text-neutral-700">Tags</span>
            <TagPicker groups={taxonomy} selectedIds={tagIds} onChange={setTagIds} />
          </div>

          {isDraft && projectedSkill && <PublishChecklist skill={projectedSkill} />}
        </aside>
      </div>
    </div>
  )
}
