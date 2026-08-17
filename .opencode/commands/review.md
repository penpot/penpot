Act as a senior software engineer and perform a thorough code review.

## Instructions

1. Load the **`code-review-and-quality`** skill — it defines the five axes, core principles (DRY, KISS, YAGNI), severity taxonomy, and output format.
2. Determine the diff or code to review from the provided context.
3. **Skip generated files, lockfile-only changes, and unrelated modifications** unless they introduce security risks.
4. Read the diff and the surrounding context for each changed file.
5. Review across all five axes: correctness, readability, architecture, security, performance.
6. Produce the review using this structure:
   - **Summary**: One-paragraph overview of the change and its impact
   - **Critical/High Findings**: Blockers that must be fixed (with file:line, severity, description, and proposed fix)
   - **Other Findings**: Medium/Low issues and suggestions
   - **Testing Recommendations**: Missing test coverage or test quality issues
   - **Positive Observations**: What was done well (brief, specific)
   - **Verdict**: Approve / Request Changes / Needs Discussion
7. For each finding:
   - State the severity (Critical / High / Medium / Low / Suggestion)
   - Identify the file and line
   - Describe failure circumstances
   - **For Critical/High**: Provide a concrete fix with a code snippet showing the corrected code
   - **For Medium/Low**: Describe the fix clearly; code snippet optional
    - If multiple approaches exist, briefly note trade-offs
8. **Perform a second review pass if the change is complex:**
   - **Complex indicators**: Critical/High findings, multiple files (>5), architectural changes, security-sensitive code, >300 lines changed
   - **Skip for simple changes**: Typo fixes, formatting, small bug fixes (<50 lines), single-file changes with no findings
   - Second pass checks:
     - Validate severity assignments: Are Critical/High findings truly blockers?
     - Catch missed issues: Edge cases, error paths, test gaps overlooked in first pass
     - Remove false positives: Discard findings that aren't real issues
     - Verify fixes: Are the proposed solutions actually correct and complete?

## Strong Rules

1. Do not invent problems. Every finding must be real and actionable.
2. Do not modify any code and do not create a commit — this command only reviews.
3. Be specific and constructive. "This could be better" is not helpful — explain why and how.
4. Prioritize by impact. One structural issue outweighs ten nits.
5. If tests are missing for new functionality, flag it as High severity.

## Context

$ARGUMENTS

## Expected Format

```
## Review Summary
[1-2 sentences on what the change does and overall assessment]

## Critical/High Findings
### [Severity] file.ts:123
**Issue**: [Description of the problem]
**Impact**: [What could go wrong]
**Fix**:
```[language]
// Current code
[problematic code]

// Fixed code
[corrected code]
```
[Optional: note trade-offs if multiple approaches exist]

## Other Findings
### [Severity] file.ts:456
**Issue**: [Description]
**Fix**: [Clear description; code snippet optional]

## Testing Recommendations
[List specific test cases that should be added]

## Positive Observations
[2-3 specific things done well]

## Verdict
[Approve / Request Changes / Needs Discussion]
[If Request Changes: list the must-fix items]
```
