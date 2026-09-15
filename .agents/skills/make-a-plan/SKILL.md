---
name: make-a-plan
description: Planning flow — research the subject of this session, produce an implementation plan with the planner skill, resolve open questions with the user in plain language, and save the final plan to .agents/plans/. Use it when the user asks to plan, design, or break down a task, in any phrasing.
---

# Make a Plan

Act as a senior software engineer: research the subject of this session in depth and
produce a well-grounded, actionable implementation plan.

If the running agent cannot write (for example, the plan agent), say so and
stop — this skill needs the build agent to save the plan.

## When to use

- The user asks to plan, design, or break down a task, in any phrasing:
  "make a plan", "how would we build X", "design an approach for Y" —
  or runs `/make-a-plan`.
- The user asks to rework or extend an existing plan (for example, after
  review findings) — revise the saved plan file in place.

Do not use it to execute a plan — that is the `implement-plan` flow.

## Instructions

1. **Produce the plan** with the `planner` skill. By default, research the
   subject of this session and draft the plan yourself. If I ask for it (for
   example, `delegated` in the user context), delegate to the `general` subagent
   instead — the delegate must also follow the `planner` skill and receive all
   the relevant session context (a review, user feedback, and so on).
2. Before asking me to decide anything, explain the plan and every open question in
   plain language. Assume I know only the high-level project goal, not the codebase,
   architecture, implementation terms, or the problem this task solves.
3. Once all decisions are answered and the plan is final, save it verbatim to the
   announced path under `.agents/plans/` (create the directory if it does not
   exist). This step is the flow's explicit authorization to write the plan
   file — the only write allowed here. If I later ask for changes, update the
   saved file directly.
4. Present me with a clear, self-contained summary of the plan's most relevant points
   only after all required decisions have been answered. Write it for someone who knows
   only the project's high-level goal and may not know the plan's low-level context.
   Explain necessary technical language in plain terms, include the problem being
   solved and the proposed outcome, and do not assume that listing technical task names
   is enough.

### Hard rule — read-only while planning

While this flow runs, act read-only: research with read-only tools only.
Never edit source files, never run builds, tests, linters, or any command that
modifies state, and never commit. The single allowed write is the plan file in
step 3. This rule expires when I approve the plan or move on to another task;
then you act as a normal build agent again.

When the plan contains open questions, do not show them as bare technical questions or
assume that I understand the technical language or technical words used in the plan.
For each question, first explain:

- What part of the user problem the decision affects.
- The relevant concept from the beginning, with a small concrete example.
- What each available option would make the system do.
- The practical benefits, costs, risks, and user-visible consequences of each option.
- Which option the planner recommends and why.

Only after that explanation, use the `question` tool to ask the decision with clear,
non-technical option labels. Put the recommended option first and mark it as
`(Recommended)`. Group related questions when their context is shared, but do not ask a
question whose meaning has not already been explained.

If I say that I do not understand a question or its choices, do not treat my previous
answer as valid. Explain the concepts again from the high-level project goal, use a more
concrete example, explain the implications, and ask the question again with the
`question` tool. Repeat this until I can make an informed choice. If one answer creates
new design consequences or additional decisions, explain those consequences before
asking any new question.

Distinguish clearly between requirements already fixed by the roadmap or existing
architecture and choices that actually require my input. Do not ask me to choose an
implementation detail when the plan can resolve it safely without changing the public
behavior. If there are no decisions that require my input, say so and present the
summary.

IMPORTANT: **Under no circumstances execute the plan. Wait for the user to review it
after all possible questions have been answered.** The final summary must explain the
problem being solved, the proposed behavior, the main user-visible workflow, important
constraints and risks, what is deliberately out of scope, and the path where the plan
is saved. Never assume that a short list of task names is enough context. End
the final response by suggesting the next steps, in this order:

1. `/review-plan` — to get a second opinion on the plan before executing it.
2. `/implement-plan` — to execute the plan from the current session context.

These are suggestions, not a required pipeline — any instruction from me
overrides them (for example, asking you to implement the plan directly).

## User context

Extra context in the user's invocation (the message that triggered this skill)
plays the role command arguments play elsewhere: for example, `delegated` to
hand the research and drafting to the `general` subagent, or corrections and
feedback about a previous plan.
