Act as a senior software engineer and perform a thorough review.

## Instructions

1. **Determine what is being reviewed** from the provided context:
   - **If it is a plan** (implementation plan, design document, task breakdown) → follow the **Plan Review** path below.
   - **If it is code** (diff, PR, code change) → follow the **Code Review** path below.

---

## Code Review Path

1. Load the **`code-review-and-quality`** skill — it defines the five axes, core principles (DRY, KISS, YAGNI), severity taxonomy, and output format.
2. Read `AGENTS.md` and follow its instructions for finding and reading all related testing documentation from memories before reviewing the code.
3. Determine the diff or code to review from the provided context.
4. **Skip generated files, lockfile-only changes, and unrelated modifications** unless they introduce security risks.
5. Read the diff and the surrounding context for each changed file.
6. Review across all five axes: correctness, readability, architecture, security, performance.
7. Produce the review using the **Code Review Format** below.
8. For each finding:
   - State the severity (Critical / High / Medium / Low / Suggestion)
   - Identify the file and line
   - Describe failure circumstances
   - **For Critical/High**: Provide a concrete fix with a code snippet showing the corrected code
   - **For Medium/Low**: Describe the fix clearly; code snippet optional
    - If multiple approaches exist, briefly note trade-offs
9. **Perform a second review pass if the change is complex:**
   - **Complex indicators**: Critical/High findings, multiple files (>5), architectural changes, security-sensitive code, >300 lines changed
   - **Skip for simple changes**: Typo fixes, formatting, small bug fixes (<50 lines), single-file changes with no findings
   - Second pass checks:
     - Validate severity assignments: Are Critical/High findings truly blockers?
     - Catch missed issues: Edge cases, error paths, test gaps overlooked in first pass
     - Remove false positives: Discard findings that aren't real issues
     - Verify fixes: Are the proposed solutions actually correct and complete?

---

## Plan Review Path

1. Load the **`plan-review`** skill — it defines the six axes, severity taxonomy, and output format.
2. Read the full plan from the provided context.
3. Review across all six axes: completeness, task quality, architecture & sequencing, risk coverage, actionability, and proposed code quality (if the plan includes implementation details).
4. Produce the review using the **Plan Review Format** below.
5. For each finding:
   - State the severity (Critical / Required / Nit / Optional / FYI)
   - Identify the section or task it refers to
   - Describe the gap or problem
   - **For Critical/Required**: Propose a concrete fix or addition
   - **For Nit/Optional**: Describe the improvement; concrete text optional
6. **Perform a second review pass if the plan is complex:**
   - **Complex indicators**: Critical findings, >10 tasks, migrations or breaking changes, security-sensitive features
   - **Skip for simple plans**: 1–2 tasks, no risks, no code proposals
   - Second pass checks:
     - Validate severity assignments
     - Catch missed gaps: edge cases, missing dependencies, unaddressed risks
     - Remove false positives
     - Verify proposed remedies are actionable

---

## Strong Rules

1. Do not invent problems. Every finding must be real and actionable.
2. Do not modify any code and do not create a commit — this command only reviews.
3. Be specific and constructive. "This could be better" is not helpful — explain why and how.
4. Prioritize by impact. One structural issue outweighs ten nits.
5. Missing tests are an issue, not a suggestion. If tests are missing or inadequate for new functionality, report it as a severity-tagged finding in the findings sections below — High severity (code) or Required (plan) — never as a recommendation.

## Context

$ARGUMENTS

## Expected Format — Code Review

```
## Review Summary
[1-2 sentences on what the change does and overall assessment]

## Critical/High Findings

### [Severity] file.ts:123
**Issue**: [Description of the problem]
**Impact**: [What could go wrong if this is not fixed]
**Fix**:

````[language]
// Current code
[problematic code]

// Fixed code
[corrected code]
[Optional: note trade-offs if multiple approaches exist]
````

### [Severity] file.ts:456
**Issue**: [Description of the problem]
**Impact**: [What could go wrong if this is not fixed]
**Fix**: [Clear description of the fix; code snippet if it clarifies]

## Other Findings

### [Severity] file.ts:789
**Issue**: [Description]
**Impact**: [Minor consequence or risk]
**Fix**: [Clear description; code snippet optional]

## Positive Observations
[2-3 specific things done well]

## Verdict
[Approve / Request Changes / Needs Discussion]
[If Request Changes: list the must-fix items]
```

## Expected Format — Plan Review

```
## Review Summary
[1-2 sentences on the plan's goal and overall assessment]

## Critical/Required Findings
### [Severity] [Section or Task N]
**Issue**: [Description of the gap or problem]
**Impact**: [What could go wrong during implementation]
**Proposed fix**: [Concrete addition or change to the plan]

## Other Findings
### [Severity] [Section or Task N]
**Issue**: [Description]
**Proposed fix**: [Clear description; concrete text optional]

## Strengths
[2-3 specific things done well in the plan]

## Verdict
[Approve / Request Changes / Needs Discussion]
[If Request Changes: list the must-fix items]
```
