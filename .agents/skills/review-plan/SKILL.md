---
name: review-plan
description: Review an implementation plan with a subagent, apply clear improvements and resolve consequential decisions with the user.
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
   model. Supply the plan path, relevant session context, whether editing is
   permitted, and these instructions:
   - Load `plan-review-criteria` and consult the project's instructions and
     relevant guidance, if present. Review the plan against the criteria.
   - If editing is permitted, apply clear, evidence-based improvements
     directly to that plan file only. Otherwise, propose them without editing.
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
3. Show what changed and list the remaining questions by ID. If any remain,
   use `question` if available (ask in the conversation if not) to offer
   **Start walkthrough** or **Stop here**. If the user stops, keep the changes
   already made and report the unresolved questions without marking the plan
   ready. If the user starts, run `@issue-walkthrough` in **decisions-only**
   mode in the parent session. Pass the questions and their IDs; wait for the
   decision table. The walkthrough does not edit or start another plan.
4. Apply the decisions to the same plan file in the parent session. Then
   resume the original reviewer with that file, the decision table, and the
   new changes. Ask it to verify the actual edits against each decision by ID
   and recheck the affected parts of the plan for consistency. Report any
   remaining problems and new questions without editing in this pass. Fix
   clear gaps in the parent session and verify again. For new consequential
   questions, return to step 3. If the original
   reviewer cannot be resumed, tell the user and delegate verification to a
   new reviewer with the full context. In read-only mode, report the proposed
   changes without applying them or claiming they were verified in the file.
5. Once the plan is ready and review feedback changed it, record the review
   only as prescribed by the plan's or project's existing conventions. For
   plans that use this skill set's `Status` and `Plan Log`, set
   `Status: reviewed` and append one UTC ISO 8601 entry summarizing the
   changes, without rewriting entries or recording commit hashes. Do not
   introduce a status or log where none is defined. If no feedback changed
   the plan, leave its metadata alone. Do not mark a plan reviewed or ready
   while required changes or decisions remain unresolved.
6. Show the final summary, plan path and verdict. Suggest `@implement-plan` only
   when the plan is ready. Never execute the plan automatically.

The only permitted write in this flow is the specified local plan file,
including its status and log. Do not stage or commit it. If a user instruction
overrides automatic editing, respect it throughout the flow and report
proposed edits instead. If the subagent cannot write, the parent may apply
its proposed edits to the specified file if permitted; otherwise report the
blocker and stop.
