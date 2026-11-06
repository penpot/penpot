---
name: posthog-timeline
description: Build a timeline from PostHog of what a Penpot user did around a bug — their actions, errors, and what came just before — and check whether other users hit the same error. Needs the PostHog MCP server.
---

# PostHog Timeline

Rebuild what a user did before and after a bug, from the PostHog events
of the Penpot projects. Then check how many other users hit the same
error.

## When to use

- The user reports a bug from a real Penpot user and wants to know what
  happened before it: "posthog timeline for this user", "what did this
  user do before the crash?", "check PostHog for this report".
- The user wants to know whether an error affects one user or many.

## Prerequisites

- The PostHog MCP server, connected to the right project:
  - **Penpot-Prod** — design.penpot.app (default).
  - **Penpot-Pre** — the pre-release environment.
- If no PostHog tools are loaded, stop and tell the user to connect the
  PostHog MCP server. Do not guess events.

## Inputs

Ask for whatever is missing, in one message:

| Input | Required | Notes |
|---|---|---|
| User email or ID | yes | Email, PostHog distinct ID, or Penpot profile ID. |
| When it happened | yes | Rough is fine ("yesterday afternoon", "2026-10-18 ~15:00 CET"). |
| What failed | yes | One sentence. |
| File link | no | A `.../workspace?team-id=...&file-id=...&page-id=...` URL narrows events to one file. |
| Project | no | Penpot-Prod unless the user says Penpot-Pre. |

Pull the IDs out of the file link: `file-id`, `team-id`, `page-id`.

## Steps

1. **Pick the project.** List the PostHog projects and switch to
   Penpot-Prod or Penpot-Pre as asked. Say which one you use.

2. **Find the person.** Look up the user by email (person property
   `email`) or by distinct ID. Note every distinct ID tied to the person;
   a user can have more than one (logged out, other devices).

3. **Learn the event names.** Before you write queries, list the event
   definitions and the properties you plan to filter on (file ID, URL,
   exception fields). Do not assume names; check them.

4. **Fetch the window.** Keep PostHog API use low: few queries, small
   windows, only the columns you need.

   - **Anchor first.** If the report is vague, first find the error
     events alone (only `timestamp` and `event`, filtered to error event
     names) over the reported period. Use the first error as the anchor.
   - **Then a narrow window:** 15 minutes before the anchor to 5 minutes
     after. Widen in steps (30, then 60 minutes before) only when the
     events so far do not explain the error.
   - Never select the whole `properties` blob for every row. Select only
     named properties, and read the full payload for the error events
     alone.

   ```sql
   SELECT timestamp, event, properties.file_id
   FROM events
   WHERE distinct_id = '<distinct id>'
     AND timestamp BETWEEN '<anchor - 15 min>' AND '<anchor + 5 min>'
   ORDER BY timestamp
   LIMIT 200
   ```

   Filter by `distinct_id` (from step 2) rather than by
   `person.properties.email`; it is cheaper. If a file link was given,
   also filter by the file ID (in the URL or in a property, whichever
   step 3 showed).

5. **Find the errors.** Pick out `$exception` events and any Penpot error
   events in the window. Keep the error type, message, and the top stack
   frames. If error tracking is available, fetch the matching issue.

6. **Check other users.** For each error, count how many distinct users
   hit the same type and message in the last 7 and 30 days, and when it
   first appeared. Group by app version or browser if the data allows.

   ```sql
   SELECT count() AS events, count(DISTINCT person_id) AS users,
          min(timestamp) AS first_seen, max(timestamp) AS last_seen
   FROM events
   WHERE event = '$exception'
     AND properties.$exception_message = '<message>'
     AND timestamp > now() - INTERVAL 30 DAY
   ```

7. **Look for a session recording.** If the events carry a
   `$session_id` and recordings exist, give the link to the session.

## Output

Reply in the user's language with:

1. **Summary** — two or three sentences: what the user was doing, what
   failed, and whether others hit it.
2. **Timeline** — a table in time order, UTC, around 20–40 rows. Collapse
   runs of the same event ("12× `$pageview` on the same file"). Mark the
   error rows and the last few actions before the first error.

   | Time (UTC) | Event | Details |
   |---|---|---|

3. **Errors** — type, message, top frames, and the PostHog issue link if
   there is one.
4. **Other users** — counts for 7 and 30 days, first and last seen, and
   any pattern (version, browser, OS).
5. **Leads** — what the timeline points to in the code, if anything. Name
   files only after you have checked they exist.
6. **Gaps** — what you could not find (no events in the window, no
   recording, user not found).

## Rules

- Read only. Never change PostHog data, insights, flags, or dashboards.
- Personal data stays in the reply. Do not paste emails or IDs into
  GitHub issues, commits, or PRs unless the user asks.
- Quote PostHog values as they are; do not invent events to fill gaps.
- Say which project and time window each query used.
