# Frontend WebSocket notifications

- Start at `app.main.data.websocket` for the notification stream and subscription lifetime, and `app.main.data.jobs/watch-job` for jobs. Preserve the configured public subpath and session identity; reinitializing or finalizing must dispose the previous receive stream.
- A newer workspace notification initialization stops the old handler even on same-file reload. Do not send a late unsubscribe for the same file: the server keeps one file subscription per connection, so it would also remove the new subscription.
- Lost membership already has a client path: the `team-membership-change` notification updates access and navigates away. The server must revoke the subscription even if the client fails to navigate; see `mem:backend/websocket-subscriptions`.
- A job's progress comes from socket events; its outcome is durable in `get-job`. Read the row on a terminal event, at startup and on socket reopen so completion before subscription or during a disconnect is visible. End after the durable outcome and let a failed read reach the caller after repo retry handling. See `mem:backend/jobs`.
- Worker progress stages are stable keys, not display strings. Translate known stages in `app.main.ui.jobs.progress` and keep a generic fallback for stages from a newer worker.
