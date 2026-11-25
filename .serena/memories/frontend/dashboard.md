# Frontend dashboard

- Start at `app.main.data.dashboard`, `app.main.data.team` and `app.main.ui.dashboard.team`. Dashboard initialization fetches projects and fonts, subscribes to the team and processes WebSocket messages for the global, profile, team and present organization topics.
- Project fetch replaces each project map rather than merging, so a removed `deleted-at` does not linger. File summaries from creation or duplication omit `:data`. Mutations can be optimistic; bulk permanent deletion and restoration use SSE progress.
- The trash takes `:will-be-deleted-at` from the backend's earlier deadline of the file and its project. A restore can finish with only a subset of the requested IDs, or none if they expired after the list loaded; build the result notification from the SSE end payload, not the attempted selection.
- Pending invitations are visible only to team admins, including owners. On `insufficient-permissions`, `fetch-invitations` clears the cached list so a demoted admin cannot keep seeing email addresses or invitation controls.
- The organization send-invitations policy does not gate listing, but gates inviting, role changes, selection, resend, deletion and link copying. Owner invitation links additionally require the team owner. Backend permission boundaries are in `mem:backend/permissions`.
- Import analysis and uploads are `mem:frontend/imports`; the separate viewer is `mem:frontend/viewer`.
