import { useState } from 'react'
import { Button } from '@/shared/components'
import { MarkdownEditor } from './MarkdownEditor'
import { SUPPORTED_TOOLS } from '@/types/skill'
import type { SupportedTool, VariantSummary } from '@/types/skill'

interface VariantEditorProps {
  /** Existing variants on the version (to disable already-used tools on create). */
  variants: VariantSummary[]
  onCreate: (tool: SupportedTool, instructions: string) => void
  onUpdate: (tool: string, instructions: string) => void
  onDelete: (tool: string) => void
  pending?: boolean
}

/**
 * Editor for a skill version's tool-specific variants. Supports creating a new
 * variant (tool + instructions), editing an existing variant's instructions,
 * and deleting a variant.
 */
export function VariantEditor({
  variants,
  onCreate,
  onUpdate,
  onDelete,
  pending = false,
}: VariantEditorProps) {
  const usedTools = new Set(variants.map((v) => v.tool))
  const availableTools = SUPPORTED_TOOLS.filter((t) => !usedTools.has(t))

  const [newTool, setNewTool] = useState<SupportedTool | ''>(
    availableTools[0] ?? '',
  )
  const [newInstructions, setNewInstructions] = useState('')

  function handleCreate() {
    if (!newTool || !newInstructions.trim()) {
      return
    }
    onCreate(newTool, newInstructions)
    setNewInstructions('')
  }

  return (
    <div className="space-y-6" data-testid="variant-editor">
      {/* Existing variants */}
      {variants.length > 0 && (
        <div className="space-y-4">
          {variants.map((variant) => (
            <ExistingVariant
              key={variant.id}
              variant={variant}
              onUpdate={onUpdate}
              onDelete={onDelete}
              pending={pending}
            />
          ))}
        </div>
      )}

      {/* Create new variant */}
      {availableTools.length > 0 ? (
        <div className="rounded-md border border-dashed border-neutral-300 p-4">
          <h4 className="mb-2 text-sm font-semibold text-neutral-800">Add a variant</h4>
          <div className="mb-3">
            <label htmlFor="new-variant-tool" className="mb-1 block text-xs font-medium text-neutral-600">
              Tool
            </label>
            <select
              id="new-variant-tool"
              value={newTool}
              onChange={(e) => setNewTool(e.target.value as SupportedTool)}
              className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              {availableTools.map((tool) => (
                <option key={tool} value={tool}>
                  {tool}
                </option>
              ))}
            </select>
          </div>
          <MarkdownEditor
            id="new-variant-instructions"
            ariaLabel="New variant instructions"
            label="Instructions"
            value={newInstructions}
            onChange={setNewInstructions}
            rows={8}
          />
          <div className="mt-3 flex justify-end">
            <Button
              size="sm"
              disabled={!newTool || !newInstructions.trim() || pending}
              onClick={handleCreate}
            >
              Add variant
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-sm text-neutral-500">
          All supported tools already have a variant on this version.
        </p>
      )}
    </div>
  )
}

interface ExistingVariantProps {
  variant: VariantSummary
  onUpdate: (tool: string, instructions: string) => void
  onDelete: (tool: string) => void
  pending: boolean
}

function ExistingVariant({ variant, onUpdate, onDelete, pending }: ExistingVariantProps) {
  const [instructions, setInstructions] = useState(variant.instructions)
  const dirty = instructions !== variant.instructions

  return (
    <div className="rounded-md border border-neutral-200 p-4">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-semibold text-neutral-800">{variant.tool}</span>
        <Button
          variant="danger"
          size="sm"
          disabled={pending}
          onClick={() => onDelete(variant.tool)}
        >
          Delete
        </Button>
      </div>
      <MarkdownEditor
        id={`variant-instructions-${variant.tool}`}
        ariaLabel={`${variant.tool} instructions`}
        value={instructions}
        onChange={setInstructions}
        rows={8}
      />
      <div className="mt-3 flex justify-end">
        <Button
          size="sm"
          disabled={!dirty || !instructions.trim() || pending}
          onClick={() => onUpdate(variant.tool, instructions)}
        >
          Save changes
        </Button>
      </div>
    </div>
  )
}
