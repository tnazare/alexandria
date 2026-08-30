import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useSkillVersion } from '@/api/skillsCatalog'
import { LoadingSpinner } from '@/shared/components'
import type { VariantSummary } from '@/types/skill'

/**
 * Full content of a specific skill version, including its variants.
 * Route: /catalog/skills/:skillId/versions/:versionNumber
 */
export function SkillVersionDetailPage() {
  const { skillId, versionNumber } = useParams<{
    skillId: string
    versionNumber: string
  }>()
  const parsedVersion = versionNumber ? Number(versionNumber) : undefined

  const { data: version, isLoading, isError } = useSkillVersion(skillId, parsedVersion)
  const [selectedTool, setSelectedTool] = useState<string>('canonical')

  const variantByTool = useMemo(() => {
    const map = new Map<string, VariantSummary>()
    for (const v of version?.variants ?? []) {
      map.set(v.tool, v)
    }
    return map
  }, [version])

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }
  if (isError || !version) {
    return <div className="py-12 text-center text-neutral-600">Version not found.</div>
  }

  const activeInstructions =
    selectedTool === 'canonical'
      ? version.canonicalInstructions
      : (variantByTool.get(selectedTool)?.instructions ??
        version.canonicalInstructions)

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <div>
        <Link
          to={`/catalog/skills/${skillId}/versions`}
          className="text-sm text-primary-600 hover:underline"
        >
          ← Back to version history
        </Link>
        <h1 className="mt-2 text-2xl font-bold text-neutral-900">
          Version {version.versionNumber}
        </h1>
        {version.changelog && (
          <p className="mt-1 text-sm text-neutral-600">{version.changelog}</p>
        )}
      </div>

      {/* Variant tabs */}
      <section>
        <div className="mb-3 flex flex-wrap gap-1 border-b border-neutral-200">
          <TabButton
            label="Canonical"
            active={selectedTool === 'canonical'}
            onClick={() => setSelectedTool('canonical')}
          />
          {version.variants.map((variant) => (
            <TabButton
              key={variant.id}
              label={variant.tool}
              active={selectedTool === variant.tool}
              onClick={() => setSelectedTool(variant.tool)}
            />
          ))}
        </div>
        <pre className="max-h-[36rem] overflow-auto whitespace-pre-wrap break-words rounded-md bg-neutral-50 p-4 font-mono text-sm text-neutral-800">
          {activeInstructions || <span className="text-neutral-400">No instructions.</span>}
        </pre>
      </section>

      {version.applicability && <Section title="Applicability" body={version.applicability} />}
      {version.whenToUse && <Section title="When to use" body={version.whenToUse} />}
      {version.examples && <Section title="Examples" body={version.examples} />}
      {version.limitations && <Section title="Limitations" body={version.limitations} />}
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

function Section({ title, body }: { title: string; body: string }) {
  return (
    <section className="space-y-2">
      <h3 className="text-lg font-semibold text-neutral-900">{title}</h3>
      <pre className="whitespace-pre-wrap break-words rounded-md bg-neutral-50 p-4 font-mono text-sm text-neutral-700">
        {body}
      </pre>
    </section>
  )
}
