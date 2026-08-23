import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { listTeams, createTeam } from '@/api/teams'
import {
  Button,
  Table,
  Pagination,
  LoadingSpinner,
  Modal,
  useToast,
  ToastContainer,
} from '@/shared/components'
import type { Column } from '@/shared/components'
import type { TeamResponse } from '@/shared/types'
import { AxiosError } from 'axios'

const TEAM_NAME_REGEX = /^[a-zA-Z0-9 \-_]+$/
const PAGE_SIZE = 20

function validateTeamName(name: string): string | null {
  if (name.length < 2) return 'Name must be at least 2 characters'
  if (name.length > 100) return 'Name must be at most 100 characters'
  if (!TEAM_NAME_REGEX.test(name))
    return 'Name can only contain letters, numbers, spaces, hyphens, and underscores'
  return null
}

export function TeamsPage() {
  const [page, setPage] = useState(0)
  const [modalOpen, setModalOpen] = useState(false)
  const [teamName, setTeamName] = useState('')
  const [teamDescription, setTeamDescription] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const { toasts, toast, dismissToast } = useToast()
  const queryClient = useQueryClient()

  const { data, isLoading, isError } = useQuery({
    queryKey: ['teams', page],
    queryFn: () => listTeams(page, PAGE_SIZE),
  })

  const createMutation = useMutation({
    mutationFn: ({ name, description }: { name: string; description: string }) =>
      createTeam(name, description),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      toast.success('Team created successfully')
      closeModal()
    },
    onError: (error: unknown) => {
      if (error instanceof AxiosError && error.response?.status === 409) {
        setNameError('A team with this name already exists')
      } else {
        toast.error('Failed to create team. Please try again.')
      }
    },
  })

  function closeModal() {
    setModalOpen(false)
    setTeamName('')
    setTeamDescription('')
    setNameError(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmedName = teamName.trim()
    const validationError = validateTeamName(trimmedName)
    if (validationError) {
      setNameError(validationError)
      return
    }
    setNameError(null)
    createMutation.mutate({ name: trimmedName, description: teamDescription.trim() })
  }

  const columns: Column<TeamResponse>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (team) => (
        <Link
          to={`/admin/teams/${team.id}`}
          className="font-medium text-primary-600 hover:text-primary-700 hover:underline"
        >
          {team.name}
        </Link>
      ),
    },
    {
      key: 'memberCount',
      header: 'Members',
      render: (team) => <span>{team.memberCount}</span>,
    },
    {
      key: 'createdAt',
      header: 'Created',
      render: (team) => (
        <span>{new Date(team.createdAt).toLocaleDateString()}</span>
      ),
    },
  ]

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }

  if (isError) {
    return (
      <div className="py-12 text-center text-red-600">
        Failed to load teams. Please try again.
      </div>
    )
  }

  return (
    <div>
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-neutral-900">Teams</h1>
        <Button onClick={() => setModalOpen(true)}>Create Team</Button>
      </div>

      <Table
        columns={columns}
        data={data?.content ?? []}
        keyExtractor={(team) => team.id}
        emptyMessage="No teams found"
      />

      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          onPageChange={setPage}
        />
      )}

      <Modal
        open={modalOpen}
        onClose={closeModal}
        title="Create Team"
        footer={
          <>
            <Button variant="secondary" onClick={closeModal}>
              Cancel
            </Button>
            <Button
              type="submit"
              form="create-team-form"
              loading={createMutation.isPending}
            >
              Create
            </Button>
          </>
        }
      >
        <form id="create-team-form" onSubmit={handleSubmit}>
          <div className="space-y-4">
            <div>
              <label
                htmlFor="team-name"
                className="mb-1 block text-sm font-medium text-neutral-700"
              >
                Name <span className="text-red-500">*</span>
              </label>
              <input
                id="team-name"
                type="text"
                value={teamName}
                onChange={(e) => {
                  setTeamName(e.target.value)
                  setNameError(null)
                }}
                className={`w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 ${
                  nameError
                    ? 'border-red-300 focus:ring-red-500'
                    : 'border-neutral-300'
                }`}
                placeholder="e.g. Platform Engineering"
                maxLength={100}
                required
                aria-describedby={nameError ? 'team-name-error' : undefined}
                aria-invalid={!!nameError}
              />
              {nameError && (
                <p id="team-name-error" className="mt-1 text-sm text-red-600">
                  {nameError}
                </p>
              )}
              <p className="mt-1 text-xs text-neutral-500">
                2–100 characters. Letters, numbers, spaces, hyphens, and underscores only.
              </p>
            </div>
            <div>
              <label
                htmlFor="team-description"
                className="mb-1 block text-sm font-medium text-neutral-700"
              >
                Description
              </label>
              <textarea
                id="team-description"
                value={teamDescription}
                onChange={(e) => setTeamDescription(e.target.value)}
                className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                placeholder="Optional description for the team"
                rows={3}
              />
            </div>
          </div>
        </form>
      </Modal>
    </div>
  )
}
