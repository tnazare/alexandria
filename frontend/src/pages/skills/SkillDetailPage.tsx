import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { AxiosError } from 'axios'
import {
  useChangeVisibility,
  useCreateVariant,
  useDeleteSkill,
  useDeleteVariant,
  usePublishSkill,
  useSkill,
  useUpdateVariant,
} from '@/api/skillsCatalog'
import { LifecycleStatusBadge } from '@/components/skill/LifecycleStatusBadge'
import { RiskLevelBadge } from '@/components/skill/RiskLevelBadge'
import { VisibilitySelector } from '@/components/skill/VisibilitySelector'
import { VariantEditor } from '@/components/skill/VariantEditor'
import { Button, LoadingSpinner, ToastContainer, useToast } from '@/shared/components'
import type {
  PublishValidationResponse,
  SupportedTool,
  VariantSummary,
  Visibility,
} from '@/types/skill'

/**
 * Full skill detail view for authoring. Route: /catalog/skills/:skillId
 *
 * <p>Shows metadata badges, the current version content, tags, and variant
 * tabs. Users with edit authority additionally get publish, visibility, edit,
 * and delete controls.</p>
 */
export function SkillDetailPage() {
  const { skillId } = useParams<{ skillId: string }>()
  const navigate = useNavigate()
  const { toasts, toast, dismissToast } = useToast()

  const { data: skill, isLoading, isError } = useSkill(skillId)
  const currentVersion = skill?.currentVersionNumber ?? 0
  const publishMutation = usePublishSkill(skillId ?? '')
  const visibilityMutation = useChangeVisibility(skillId ?? '')
  const deleteMutation = useDeleteSkill()
  const createVariantMutation = useCreateVariant(skillId ?? '', currentVersion)
  const updateVariantMutation = useUpdateVariant(skillId ?? '', currentVersion)
  const deleteVariantMutation = useDeleteVariant(skillId ?? '', currentVersion)

  const [selectedTool, setSelectedTool] = useState<string>('canonical')
  const [showVariantEditor, setShowVariantEditor] = useState(false)

  // Edit authority is enforced server-side. Team membership isn't present in the
  // token, so the UI optimistically shows edit controls (admins always qualify)
  // and lets the API reject unauthorized actions with 403.
  const canEdit = true

  const variantByTool = useMemo(() => {
    const map = new Map<string, VariantSummary>()
    for (const v of skill?.variants ?? []) {
      map.set(v.tool, v)
    }
    return map
  }, [skill])

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }
  if (isError || !skill) {
    return <div className="py-12 text-center text-neutral-600">Skill not found.</div>
  }

  const isDraft = skill.lifecycleStatus === 'Draft'

  function handlePublish() {
    publishMutation.mutate(undefined, {
      onSuccess: () => toast.success('Skill published'),
      onError: (error: unknown) => {
        if (error instanceof AxiosError && error.response?.status === 422) {
          const body = error.response.data as PublishValidationResponse
          toast.error(
            `Cannot publish. Missing: ${(body.missingFields ?? []).join(', ')}`,
          )
        } else if (error instanceof AxiosError && error.response?.status === 403) {
          toast.error('You do not have permission to publish this skill')
        } else {
          toast.error('Failed to publish the skill')
        }
      },
    })
  }

  function handleVisibilityChange(visibility: Visibility) {
    visibilityMutation.mutate(
      { visibility },
      {
        onSuccess: () => toast.success('Visibility updated'),
        onError: () => toast.error('Failed to update visibility'),
      },
    )
  }

  function handleDelete() {
    if (!skillId) return
    if (!window.confirm('Delete this draft skill? This cannot be undone.')) {
      return
    }
    deleteMutation.mutate(skillId, {
      onSuccess: () => {
        toast.success('Skill deleted')
        void navigate('/catalog')
      },
      onError: (error: unknown) => {
        if (error instanceof AxiosError && error.response?.status === 409) {
          toast.error('Only draft skills can be deleted')
        } else {
          toast.error('Failed to delete the skill')
        }
      },
    })
  }

  function handleCreateVariant(tool: SupportedTool, instructions: string) {
    createVariantMutation.mutate(
      { tool, instructions },
      {
        onSuccess: () => toast.success(`Variant for ${tool} created`),
        onError: () => toast.error('Failed to create variant'),
      },
    )
  }

  function handleUpdateVariant(tool: string, instructions: string) {
    updateVariantMutation.mutate(
      { tool, request: { tool: tool as SupportedTool, instructions } },
      {
        onSuccess: () => toast.success(`Variant for ${tool} updated`),
        onError: () => toast.error('Failed to update variant'),
      },
    )
  }

  function handleDeleteVariant(tool: string) {
    deleteVariantMutation.mutate(tool, {
      onSuccess: () => toast.success(`Variant for ${tool} deleted`),
      onError: () => toast.error('Failed to delete variant'),
    })
  }

  const variantMutationPending =
    createVariantMutation.isPending ||
    updateVariantMutation.isPending ||
    deleteVariantMutation.isPending

  const activeInstructions =
    selectedTool === 'canonical'
      ? skill.canonicalInstructions
      : (variantByTool.get(selectedTool)?.instructions ?? skill.canonicalInstructions)

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      {/* Header */}
      <header className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-neutral-900">{skill.name}</h1>
          <LifecycleStatusBadge status={skill.lifecycleStatus} size="md" />
          <RiskLevelBadge riskLevel={skill.riskLevel} size="md" />
        </div>
        <p className="text-neutral-600">{skill.description}</p>
        <div className="flex flex-wrap gap-4 text-sm text-neutral-500">
          {skill.ownerTeamName && <span>Team: {skill.ownerTeamName}</span>}
          <span>Version {skill.currentVersionNumber}</span>
          <span>Visibility: {skill.visibility}</span>
          <Link
            to={`/catalog/skills/${skill.id}/versions`}
            className="text-primary-600 hover:underline"
          >
            Version history
          </Link>
        </div>
      </header>

      {/* Edit-authority controls */}
      {canEdit && (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-neutral-200 bg-neutral-50 p-3">
          <Button size="sm" onClick={() => navigate(`/catalog/skills/${skill.id}/edit`)}>
            Edit
          </Button>
          {isDraft && (
            <Button
              size="sm"
              variant="primary"
              loading={publishMutation.isPending}
              onClick={handlePublish}
            >
              Publish
            </Button>
          )}
          {isDraft && (
            <Button
              size="sm"
              variant="danger"
              loading={deleteMutation.isPending}
              onClick={handleDelete}
            >
              Delete
            </Button>
          )}
          <div className="ml-auto w-56">
            <VisibilitySelector
              value={skill.visibility}
              onChange={handleVisibilityChange}
              disabled={visibilityMutation.isPending}
            />
          </div>
        </div>
      )}

      {/* Tags */}
      {skill.tags.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {skill.tags.map((tag) => (
            <span
              key={tag.id}
              className="inline-flex items-center gap-1 rounded-full bg-neutral-100 px-2 py-0.5 text-xs text-neutral-700"
            >
              <span className="text-neutral-400">{tag.type}:</span>
              {tag.value}
            </span>
          ))}
        </div>
      )}

      {/* Variant tabs */}
      <section>
        <div className="mb-3 flex flex-wrap gap-1 border-b border-neutral-200">
          <TabButton
            label="Canonical"
            active={selectedTool === 'canonical'}
            onClick={() => setSelectedTool('canonical')}
          />
          {skill.variants.map((variant) => (
            <TabButton
              key={variant.id}
              label={variant.tool}
              active={selectedTool === variant.tool}
              onClick={() => setSelectedTool(variant.tool)}
            />
          ))}
        </div>
        <pre className="max-h-[36rem] overflow-auto whitespace-pre-wrap break-words rounded-md bg-neutral-50 p-4 font-mono text-sm text-neutral-800">
          {activeInstructions || (
            <span className="text-neutral-400">No instructions.</span>
          )}
        </pre>
      </section>

      {/* Variant management (edit authority) */}
      {canEdit && (
        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold text-neutral-900">
              Manage variants (v{skill.currentVersionNumber})
            </h3>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => setShowVariantEditor((s) => !s)}
            >
              {showVariantEditor ? 'Hide' : 'Manage variants'}
            </Button>
          </div>
          {showVariantEditor && (
            <VariantEditor
              variants={skill.variants}
              onCreate={handleCreateVariant}
              onUpdate={handleUpdateVariant}
              onDelete={handleDeleteVariant}
              pending={variantMutationPending}
            />
          )}
        </section>
      )}

      {/* Additional content sections */}
      {skill.applicability && (
        <DetailSection title="Applicability" body={skill.applicability} />
      )}
      {skill.whenToUse && <DetailSection title="When to use" body={skill.whenToUse} />}
      {skill.examples && <DetailSection title="Examples" body={skill.examples} />}
      {skill.limitations && (
        <DetailSection title="Limitations" body={skill.limitations} />
      )}

      {skill.supportedTools.length > 0 && (
        <p className="text-sm text-neutral-500">
          Supported tools: {skill.supportedTools.join(', ')}
        </p>
      )}
    </div>
  )
}

function TabButton({
  label,
  active,
  onClick,
}: {
  label: string
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
        active
          ? 'border-primary-600 text-primary-700'
          : 'border-transparent text-neutral-500 hover:text-neutral-800'
      }`}
    >
      {label}
    </button>
  )
}

function DetailSection({ title, body }: { title: string; body: string }) {
  return (
    <section className="space-y-2">
      <h3 className="text-lg font-semibold text-neutral-900">{title}</h3>
      <pre className="whitespace-pre-wrap break-words rounded-md bg-neutral-50 p-4 font-mono text-sm text-neutral-700">
        {body}
      </pre>
    </section>
  )
}
