---
name: review-plan
description: Review an implementation plan with a subagent, apply clear improvements and resolve consequential decisions with the user.
slash: true
---

# Review Plan

Review an unimplemented plan with a subagent and improve the plan without
implementing it. Use this flow when the user asks for a plan review or invokes
`@review-plan`.

## Flow

1. Identify and read the complete plan and its file. If the target or path is
   ambiguous, ask. If no saved file exists, ask for a path or have the user save
   it first. Do not edit a completed plan: propose a derived plan instead.
2. Delegate to `general`, unless the user specifies a different subagent or
   model. Supply the plan path, relevant session context and these instructions:
   - Load `plan-review-criteria`, read `AGENTS.md` and the relevant module and
     testing memories, and review the plan against the criteria.
   - Apply clear, evidence-based improvements directly to that plan file only.
     Preserve the goal, scope, agreed decisions and intended behavior. Examples:
     clarify tasks, add missing tests, correct dependencies and specify actual
     verification commands. Do not guess commands or invent findings.
   - A change that affects the goal, user-visible behavior, scope, tradeoffs or
     an existing decision needs the user's decision. Leave it unresolved; report
     concrete options, consequences and a recommendation. Do not ask questions
     from the subagent.
   - Return a concise summary of applied changes, remaining decisions with
     stable IDs, evidence and recommendations, and an honest provisional
     verdict. Retain the subagent session ID for follow-up if available.
   - Do not change source files, other plans or documents, run state-changing
     checks, commit, or implement the plan. If the plan is not writable, report
     this and return the proposed edits instead.
3. Show the summary. If decisions remain, start `@issue-walkthrough` immediately
   in the parent session with those questions and their IDs. Follow its one
   question per turn and read-only rules. Do not ask questions that the code or
   existing decisions settle. Wait for every answer.
4. Resume the same subagent with the decision table and plan path. It applies
   the decisions to the plan, rechecks consistency and reports changes and any
   newly discovered consequential decisions. Repeat the walkthrough for new
   decisions, then apply those answers. If the original session cannot be
   resumed, tell the user and apply the changes in the parent or delegate a new
   subagent with the full context. If the reviewer cannot write, apply its
   proposed edits in the parent only if the plan file is writable; otherwise
   explain the blocker and stop.
5. Once all decisions are resolved and the plan is coherent, update its
   metadata: when review feedback changed the plan, set `Status: reviewed` and
   append one UTC ISO 8601 `Review Log` entry for this review, summarizing the
   changes. Preserve prior log entries. If no feedback changed the plan, leave
   status and log unchanged. Never record commit hashes. If decisions or
   required changes remain unresolved, do not claim the plan is ready or mark
   it reviewed.
6. Show the final summary, plan path and verdict. Suggest `@implement-plan` only
   when the plan is ready. Never execute the plan automatically.

The only permitted write in this flow is the specified local plan file,
including its status and log. Do not stage or commit it. If a user instruction
overrides automatic editing, respect it and report findings without editing.
