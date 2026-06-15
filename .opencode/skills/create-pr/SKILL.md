---
name: create-pr
description: Create a GitHub PR following Penpot conventions, with a concise engineer-focused description
---

# Create Pull Request

Create a GitHub PR with proper title format and a concise description that explains reasoning, not implementation details.

## When to Use

- Opening a new pull request
- The user asks to create a PR
- Code changes are ready and committed

## Workflow

### 1. Verify Prerequisites

```bash
git branch --show-current
git log --oneline main..HEAD
```

### 2. Check if Branch is Pushed

```bash
BRANCH=$(git branch --show-current)
if git ls-remote --heads origin "$BRANCH" | grep -q "$BRANCH"; then
  echo "Branch is pushed, proceeding with PR creation"
else
  echo "ERROR: Branch '$BRANCH' is not pushed to remote. Please push the branch first."
  exit 1
fi
```

**If the branch is not pushed, STOP here and ask the user to push it. The LLM does not have push permissions.**

### 3. Create PR Body

Write to `/tmp/pr-body.md` to avoid shell quoting issues:

```bash
cat > /tmp/pr-body.md << 'EOF'
**Note:** This PR was created with AI assistance.

## What

<one paragraph: the problem or feature, user-facing impact>

## Why

<root cause or motivation, why this change was necessary>

## How

<high-level approach, key technical decisions>
EOF
```

### 4. Create the PR

Follow title and description format from `mem:workflow/creating-prs` and `mem:workflow/creating-commits`.

```bash
gh pr create --base main --project "Main" --title "<title>" --body-file /tmp/pr-body.md
```

### 5. What NOT to Include

- ❌ List of files changed (visible in diff)
- ❌ Testing steps (CI handles this)
- ❌ Screenshots unless UI-visible
- ❌ Migration notes unless breaking changes
- ❌ Regression fixes introduced during the PR (they're part of the development process, not the feature)

## Key Principles

- **Write for humans.** The diff shows what changed. The description explains why.
- **Be concise.** Focus on reasoning: What was the problem? Why did it happen? How did you solve it?
- **Skip the obvious.** Don't explain what `git diff` already shows.
