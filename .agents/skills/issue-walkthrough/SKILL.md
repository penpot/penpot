---
name: issue-walkthrough
description: Walk through findings or open questions one decision at a time. Record clear resolutions without asking. Return decisions to the calling review or offer to prepare a plan where appropriate. Never edit or implement.
---

# Issue Walkthrough

Turn a list of findings or open questions into a decision table. Ask the user
only about decisions the evidence cannot settle. Record clear resolutions
without applying them.

## When to use

- I ask to go through the findings of a review, one by one.
- I ask to discuss a list of issues or open questions and decide each one.
- I ask what to do about something and want options, not an implementation.
- I mention `@issue-walkthrough`.

## Hard rules

- **Read-only for the whole walkthrough.** No edits, no commits, no builds, no
  test runs that change state. Read-only commands, reads and `SELECT`s are fine.
  Any later edits belong to a separate flow, not to this walkthrough.
- **One issue per turn.** Explain it, ask, and wait. Do not queue the next
  explanation in the same message.
- **Never implement.** Not even the "obvious one-line" fix derived from an item.
- **Never invent a finding.** Before presenting an item, check it in the code.
  If it does not hold, say so and drop it.
- **Do not merge items** to finish faster. Only group two items when they share
  the same decision and the same context.

## Before the first issue

1. Fix the list of items and keep their IDs (`R1`, `M2`, `L3`, …). The IDs are
   how we refer to each decision later, in the plan and in the commits.
2. Verify each item against the relevant code, plan, or other available
   evidence. Say when the evidence is insufficient; do not run tests to fill
   that gap.
3. Read relevant earlier plans, project guidance and decisions, if they exist.
   If an item reopens something already decided, say it before asking: the
   decision, its date, and what changed since.
4. Separate **Decisions to ask** from **Clear resolutions**. For each clear
   resolution, show its ID, proposed remedy, and a short reason it follows
   from the evidence or an agreed decision. Do not ask about it or describe
   it as applied. Ask when the remedy would change scope or intended behavior,
   reopen a decision, or require choosing between meaningful tradeoffs.
5. Show the order of the questions by ID and title, then ask the first one.
   If none remain, skip the question round and go to closing.

## Per issue: the fixed shape

In this order, in plain words:

**Starting situation.** State the goal of this part of the system. Name the
objects involved, their roles and initial state, and what action or condition
brings this case about. Say what normally happens and what is different here.
Separate facts and earlier decisions from assumptions and example values. If
an unconfirmed assumption changes the scope or the options, ask about that
assumption first and wait before asking about its consequences.

**What happens.** The situation, with `file:line` and the failing case. When it
exists, the evidence goes here: the test error, the measured query, the
`EXPLAIN`. Explain why the case can occur and what the evidence shows. If the
case is hypothetical, say so. Explain every technical term the first time.

**Why it matters.** What breaks, for whom, and when. If it never breaks and is
only cost or consistency, say exactly that. Never inflate an item.

**Options.** Two to four real options. For each: what changes, cost, and risk.
Include "do nothing" when it is a real choice. When an option contradicts an
earlier decision of mine, say so with the decision and its date.

**Recommendation.** One option and the reason, in two or three sentences.

Then ask, and stop.

## The question

- One `question` call per issue, unless two items share the same decision and
  context. If the tool is unavailable, ask in the conversation and wait.
- The recommended option goes first and is marked `(Recommended)`.
- Labels are short (1-5 words) and readable without the explanation.
- Every description says what the option makes the system do and its practical
  consequence (downtime, data, operator work, future maintenance).
- Never ask about something the code or the plan can settle alone.

## When I override the options

My free-form answer wins. Do not re-ask, do not argue, do not treat my previous
answer as valid if I said I did not understand.

- Restate the resulting shape in one or two sentences — the concrete SQL,
  function, text or command you would write — so I can confirm you understood.
- If the override creates a new consequence or an extra decision, explain it
  before the next issue.
- Record exactly what I said, not the option label.

## When I ask for more context

Address the premise the user is questioning before giving another example or
repeating the decision. Restate the objects, their starting state, and why
the case can happen. Explain when it does not matter. Then ask only about
what is still undecided. Do not move to the next item until the current one
has an answer.

## Closing and handover

When every item has a decision:

1. Print the decision table: `ID | finding | decision | task sketch`. One row
   per item, including clear resolutions and any "do nothing" decisions, with
   the reason. Distinguish user decisions from clear resolutions.
2. Say which items are fixed requirements and which are optional.
3. In **decisions-only** mode, return the table to the calling flow and stop.
   Do not offer planning or make edits. The caller owns any follow-up.
4. Otherwise, if there is work to plan and `@make-a-plan` accepts the scope
   under the project rules, use `question` if available (ask in the
   conversation if not) to offer **Prepare a plan** or **Stop**. Hand over the
   table only if the user chooses to plan. Carry forward the agreed decisions
   and clear resolutions without reopening them.
5. If planning does not apply, return the table and stop. Do not apply it;
   direct corrections require a separate request.

## Check before each turn

Read your own message as someone who does not know this code:

- Can I decide with what is on screen, without opening the editor?
- Do I know what objects are involved, their starting state, and what brings
  this case about before I read the problem or the question?
- Is the failing case concrete, and is the reason it is real shown?
- Is there a recommendation, and does it say why this option and not another?
- On a decision turn, is there exactly one question, with the recommended
  option first?
- Did you touch a file? It must be no.

## User context

Text passed after `@issue-walkthrough` can supply the findings or open
questions, a review reference, a plan path, or a scope limit. Explicit input
overrides session inference but never higher-level instructions.
