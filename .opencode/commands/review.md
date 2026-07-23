Act as a senior software engineer and perform a thorough code review.

## Instructions

1. Load the **`code-review-and-quality`** skill — it defines the five axes, core principles (DRY, KISS, YAGNI), severity taxonomy, and output format.
2. Determine the diff or code to review from the provided context.
3. Read the diff and the surrounding context for each changed file.
4. Review across all five axes: correctness, readability, architecture, security, performance.
5. Produce the review using the **Review Output** format from the skill (Summary → Critical/High → Other Findings → Refactoring → Testing Recommendations → Positive Observations → Final Verdict).
6. For each finding: state the severity (Critical / High / Medium / Low / Suggestion), identify the file and line, describe failure circumstances, and propose a concrete fix.
7. Do not invent problems. Every finding must be real and actionable.

Do not modify any code and do not create a commit — this command only reviews.

## Context

$ARGUMENTS
