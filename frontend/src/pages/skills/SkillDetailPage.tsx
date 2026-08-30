import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { AxiosError } from 'axios'
import {
  useArchiveSkill,
  useBlockSkill,
  useChangeVisibility,
  useCreateVariant,
  useDeleteSkill,
  useDeleteVariant,
  useDeprecateSkill,
  usePublishSkill,
  useRestoreSkill,
  useSkill,
  useUpdateVariant,
} from '@/api/skillsCatalog'
import { useAuth } from '@/auth'
import { LifecycleStatusBadge } from '@/components/skill/LifecycleStatusBadge'
import { RiskLevelBadge } from '@/components/skill/RiskLevelBadge'
import { VisibilitySelector } from '@/components/skill/VisibilitySelector'
import { VariantEditor } from '@/components/skill/VariantEditor'
import { Button, LoadingSpinner, ToastContainer, useToast } from '@/shared/components'
import {
  allowedLifecycleActions,
  type LifecycleAction,
  type PublishValidationResponse,
  type SupportedTool,
  type VariantSummary,
  type Visibility,
} from '@/types/skill'

/** Roles allowed to perform lifecycle governance transitions. */
const GOVERNANCE_ROLES = ['Org_Admin', 'Catalog_Admin']

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
  const { userRole } = useAuth()

  const { data: skill, isLoading, isError } = useSkill(skillId)
  const currentVersion = skill?.currentVersionNumber ?? 0
  const publishMutation = usePublishSkill(skillId ?? '')
  const visibilityMutation = useChangeVisibility(skillId ?? '')
  const deleteMutation = useDeleteSkill()
  const deprecateMutation = useDeprecateSkill(skillId ?? '')
  const blockMutation = useBlockSkill(skillId ?? '')
  const archiveMutation = useArchiveSkill(skillId ?? '')
  const restoreMutation = useRestoreSkill(skillId ?? '')
  const createVariantMutation = useCreateVariant(skillId ?? '', currentVersion)
  const updateVariantMutation = useUpdateVariant(skillId ?? '', currentVersion)
  const deleteVariantMutation = useDeleteVariant(skillId ?? '', currentVersion)

  const [selectedTool, setSelectedTool] = useState<string>('canonical')
  const [showVariantEditor, setShowVariantEditor] = useState(false)
  const [showDeprecateForm, setShowDeprecateForm] = useState(false)
  const [deprecateReason, setDeprecateReason] = useState('')

  // Edit authority is enforced server-side. Team membership isn't present in the
  // token, so the UI optimistically shows edit controls (admins always qualify)
  // and lets the API reject unauthorized actions with 403.
  const canEdit = true

  // Governance transitions (deprecate/block/archive/restore) are restricted to
  // catalog/org admins. The UI mirrors the backend legality table; the server
  // remains authoritative and rejects illegal transitions with 409.
  const canGovern = Boolean(userRole && GOVERNANCE_ROLES.includes(userRole))

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

  function handleLifecycleError(action: LifecycleAction, error: unknown) {
    if (error instanceof AxiosError && error.response?.status === 403) {
      toast.error('You do not have permission to change this skill\u2019s lifecycle')
    } else if (error instanceof AxiosError && error.response?.status === 409) {
      toast.error(`Cannot ${action} the skill from its current status`)
    } else {
      toast.error(`Failed to ${action} the skill`)
    }
  }

  function handleDeprecate() {
    const reason = deprecateReason.trim()
    deprecateMutation.mutate(reason.length > 0 ? reason : undefined, {
      onSuccess: () => {
        toast.success('Skill deprecated')
        setShowDeprecateForm(false)
        setDeprecateReason('')
      },
      onError: (error: unknown) => handleLifecycleError('deprecate', error),
    })
  }

  function handleBlock() {
    if (!window.confirm('Block this skill? It will be hidden from search and listings.')) {
      return
    }
    blockMutation.mutate(undefined, {
      onSuccess: () => toast.success('Skill blocked'),
      onError: (error: unknown) => handleLifecycleError('block', error),
    })
  }

  function handleArchive() {
    if (!window.confirm('Archive this skill? It will be hidden from default listings.')) {
      return
    }
    archiveMutation.mutate(undefined, {
      onSuccess: () => toast.success('Skill archived'),
      onError: (error: unknown) => handleLifecycleError('archive', error),
    })
  }

  function handleRestore() {
    restoreMutation.mutate(undefined, {
      onSuccess: () => toast.success('Skill restored'),
      onError: (error: unknown) => handleLifecycleError('restore', error),
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

  const lifecycleActions = allowedLifecycleActions(skill.lifecycleStatus)
  const lifecyclePending =
    deprecateMutation.isPending ||
    blockMutation.isPending ||
    archiveMutation.isPending ||
    restoreMutation.isPending
  const isDeprecated = skill.lifecycleStatus === 'Deprecated'
  const isBlocked = skill.lifecycleStatus === 'Blocked'
  const isArchived = skill.lifecycleStatus === 'Archived'

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

      {/* Lifecycle status banners */}
      {isDeprecated && (
        <div
          role="alert"
          className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800"
        >
          <p className="font-semibold">This skill is deprecated.</p>
          {skill.deprecationReason ? (
            <p className="mt-1">{skill.deprecationReason}</p>
          ) : (
            <p className="mt-1 text-amber-700">
              It is discouraged for new work and may be removed in the future.
            </p>
          )}
        </div>
      )}
      {isBlocked && (
        <div
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-800"
        >
          <p className="font-semibold">This skill is blocked.</p>
          <p className="mt-1">
            It is hidden from search and listings and cannot be used until restored.
          </p>
        </div>
      )}
      {isArchived && (
        <div
          role="alert"
          className="rounded-md border border-neutral-300 bg-neutral-100 p-3 text-sm text-neutral-700"
        >
          <p className="font-semibold">This skill is archived.</p>
          <p className="mt-1">It is hidden from default listings.</p>
        </div>
      )}

      {/* Governance controls (catalog/org admins only) */}
      {canGovern && lifecycleActions.length > 0 && (
        <div className="space-y-3 rounded-md border border-neutral-200 bg-white p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-neutral-700">Governance:</span>
            {lifecycleActions.includes('deprecate') && (
              <Button
                size="sm"
                variant="secondary"
                disabled={lifecyclePending}
                onClick={() => setShowDeprecateForm((s) => !s)}
              >
                Deprecate
              </Button>
            )}
            {lifecycleActions.includes('block') && (
              <Button
                size="sm"
                variant="danger"
                loading={blockMutation.isPending}
                disabled={lifecyclePending}
                onClick={handleBlock}
              >
                Block
              </Button>
            )}
            {lifecycleActions.includes('archive') && (
              <Button
                size="sm"
                variant="secondary"
                loading={archiveMutation.isPending}
                disabled={lifecyclePending}
                onClick={handleArchive}
              >
                Archive
              </Button>
            )}
            {lifecycleActions.includes('restore') && (
              <Button
                size="sm"
                variant="primary"
                loading={restoreMutation.isPending}
                disabled={lifecyclePending}
                onClick={handleRestore}
              >
                Restore
              </Button>
            )}
          </div>
          {showDeprecateForm && lifecycleActions.includes('deprecate') && (
            <div className="space-y-2 border-t border-neutral-100 pt-3">
              <label
                htmlFor="deprecate-reason"
                className="block text-sm font-medium text-neutral-700"
              >
                Deprecation reason (optional)
              </label>
              <textarea
                id="deprecate-reason"
                rows={3}
                maxLength={1000}
                value={deprecateReason}
                onChange={(e) => setDeprecateReason(e.target.value)}
                placeholder="Why is this skill being deprecated? What should be used instead?"
                className="w-full rounded-md border border-neutral-300 p-2 text-sm focus:border-primary-500 focus:outline-none"
              />
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="primary"
                  loading={deprecateMutation.isPending}
                  onClick={handleDeprecate}
                >
                  Confirm deprecation
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={deprecateMutation.isPending}
                  onClick={() => {
                    setShowDeprecateForm(false)
                    setDeprecateReason('')
                  }}
                >
                  Cancel
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

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
