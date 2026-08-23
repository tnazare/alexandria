import { useState } from 'react'
import { useParams, Link } from 'react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getTeam, addMember, removeMember } from '@/api/teams'
import { listUsers } from '@/api/users'
import {
  Button,
  LoadingSpinner,
  Modal,
  Table,
  useToast,
  ToastContainer,
} from '@/shared/components'
import type { Column } from '@/shared/components'
import type { TeamMemberResponse } from '@/shared/types'
import { AxiosError } from 'axios'

export function TeamDetailPage() {
  const { teamId } = useParams<{ teamId: string }>()
  const [addModalOpen, setAddModalOpen] = useState(false)
  const [selectedUserId, setSelectedUserId] = useState('')
  const [selectedRole, setSelectedRole] = useState('member')
  const [addError, setAddError] = useState<string | null>(null)
  const { toasts, toast, dismissToast } = useToast()
  const queryClient = useQueryClient()

  const {
    data: team,
    isLoading,
    isError,
    error,
  } = useQuery({
    queryKey: ['team', teamId],
    queryFn: () => getTeam(teamId!),
    enabled: !!teamId,
  })

  // Fetch users for the add member dropdown
  const { data: usersData } = useQuery({
    queryKey: ['users-for-add-member'],
    queryFn: () => listUsers(0, 100),
    enabled: addModalOpen,
  })

  const addMemberMutation = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: string }) =>
      addMember(teamId!, userId, role),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['team', teamId] })
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      toast.success('Member added successfully')
      closeAddModal()
    },
    onError: (err: unknown) => {
      if (err instanceof AxiosError) {
        if (err.response?.status === 409) {
          setAddError('This user is already a member of the team')
        } else if (err.response?.status === 404) {
          setAddError('User not found')
        } else {
          toast.error('Failed to add member. Please try again.')
        }
      } else {
        toast.error('Failed to add member. Please try again.')
      }
    },
  })

  const removeMemberMutation = useMutation({
    mutationFn: (userId: string) => removeMember(teamId!, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['team', teamId] })
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      toast.success('Member removed')
    },
    onError: (err: unknown) => {
      if (err instanceof AxiosError && err.response?.status === 409) {
        toast.error('Cannot remove the last owner of the team')
      } else {
        toast.error('Failed to remove member. Please try again.')
      }
    },
  })

  function closeAddModal() {
    setAddModalOpen(false)
    setSelectedUserId('')
    setSelectedRole('member')
    setAddError(null)
  }

  function handleAddMember(e: React.FormEvent) {
    e.preventDefault()
    if (!selectedUserId) {
      setAddError('Please select a user')
      return
    }
    setAddError(null)
    addMemberMutation.mutate({ userId: selectedUserId, role: selectedRole })
  }

  function handleRemoveMember(userId: string) {
    removeMemberMutation.mutate(userId)
  }

  // Filter out existing members from the user selection
  const existingMemberIds = new Set(team?.members.map((m) => m.userId) ?? [])
  const availableUsers =
    usersData?.content.filter((u) => !existingMemberIds.has(u.id)) ?? []

  const columns: Column<TeamMemberResponse>[] = [
    {
      key: 'email',
      header: 'Email',
      render: (member) => (
        <span className="font-medium text-neutral-900">{member.email}</span>
      ),
    },
    {
      key: 'displayName',
      header: 'Display Name',
      render: (member) => (
        <span className="text-neutral-600">{member.displayName ?? '—'}</span>
      ),
    },
    {
      key: 'teamRole',
      header: 'Role',
      render: (member) => (
        <span className="inline-flex items-center rounded-full bg-neutral-100 px-2.5 py-0.5 text-xs font-medium capitalize text-neutral-700">
          {member.teamRole}
        </span>
      ),
    },
    {
      key: 'joinedAt',
      header: 'Joined',
      render: (member) => (
        <span>{new Date(member.joinedAt).toLocaleDateString()}</span>
      ),
    },
    {
      key: 'actions',
      header: '',
      render: (member) => (
        <Button
          variant="danger"
          size="sm"
          onClick={() => handleRemoveMember(member.userId)}
          disabled={removeMemberMutation.isPending}
        >
          Remove
        </Button>
      ),
      className: 'text-right',
    },
  ]

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }

  if (isError) {
    const axiosErr = error instanceof AxiosError ? error : null
    if (axiosErr?.response?.status === 404) {
      return (
        <div className="py-12 text-center">
          <p className="text-lg text-neutral-600">Team not found</p>
          <Link
            to="/admin/teams"
            className="mt-4 inline-block text-primary-600 hover:underline"
          >
            ← Back to Teams
          </Link>
        </div>
      )
    }
    return (
      <div className="py-12 text-center text-red-600">
        Failed to load team details. Please try again.
      </div>
    )
  }

  if (!team) return null

  return (
    <div>
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      <div className="mb-2">
        <Link
          to="/admin/teams"
          className="text-sm text-neutral-500 hover:text-neutral-700"
        >
          ← Back to Teams
        </Link>
      </div>

      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-neutral-900">{team.name}</h1>
          {team.description && (
            <p className="mt-1 text-sm text-neutral-600">{team.description}</p>
          )}
          <p className="mt-1 text-xs text-neutral-400">Slug: {team.slug}</p>
        </div>
        <Button onClick={() => setAddModalOpen(true)}>Add Member</Button>
      </div>

      <h2 className="mb-3 text-lg font-medium text-neutral-800">
        Members ({team.members.length})
      </h2>

      <Table
        columns={columns}
        data={team.members}
        keyExtractor={(member) => member.userId}
        emptyMessage="No members yet"
      />

      <Modal
        open={addModalOpen}
        onClose={closeAddModal}
        title="Add Member"
        footer={
          <>
            <Button variant="secondary" onClick={closeAddModal}>
              Cancel
            </Button>
            <Button
              type="submit"
              form="add-member-form"
              loading={addMemberMutation.isPending}
            >
              Add
            </Button>
          </>
        }
      >
        <form id="add-member-form" onSubmit={handleAddMember}>
          <div className="space-y-4">
            <div>
              <label
                htmlFor="member-user"
                className="mb-1 block text-sm font-medium text-neutral-700"
              >
                User <span className="text-red-500">*</span>
              </label>
              <select
                id="member-user"
                value={selectedUserId}
                onChange={(e) => {
                  setSelectedUserId(e.target.value)
                  setAddError(null)
                }}
                className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                required
                aria-describedby={addError ? 'add-member-error' : undefined}
                aria-invalid={!!addError}
              >
                <option value="">Select a user</option>
                {availableUsers.map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.email}
                    {user.displayName ? ` (${user.displayName})` : ''}
                  </option>
                ))}
              </select>
              {addError && (
                <p id="add-member-error" className="mt-1 text-sm text-red-600">
                  {addError}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="member-role"
                className="mb-1 block text-sm font-medium text-neutral-700"
              >
                Team Role
              </label>
              <select
                id="member-role"
                value={selectedRole}
                onChange={(e) => setSelectedRole(e.target.value)}
                className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              >
                <option value="member">Member</option>
                <option value="owner">Owner</option>
              </select>
            </div>
          </div>
        </form>
      </Modal>
    </div>
  )
}
