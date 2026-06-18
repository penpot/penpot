---
name: create-pr
description: Create or update a GitHub PR following Penpot conventions, with a concise engineer-focused description
---

# Pull Request (Create or Update)

Create or update a GitHub PR with proper title format and a concise description that explains reasoning, not implementation details.

## When to Use

- Opening a new pull request
- Updating an existing PR's title or description
- The user asks to create or update a PR
- Code changes are ready and committed

## Conventions

All PR conventions (title format, body structure, writing principles, what NOT to include) are documented in `mem:workflow/creating-prs`. This skill covers the procedure only.

## Workflow A: Creating a New PR

### A1. Verify Prerequisites

```bash
git branch --show-current
git log --oneline main..HEAD
```

### A2. Check if Branch is Pushed

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

### A3. Create PR Body

Write the body to `/tmp/pr-body.md` using the template from `mem:workflow/creating-prs` (Description Body section):

```bash
cat > /tmp/pr-body.md << 'EOF'
**Note:** This PR was created with AI assistance as part of the Penpot MCP self-improvement initiative.

## What

<one paragraph: the problem or feature, user-facing impact>

## Why

<root cause or motivation, why this change was necessary>

## How

<high-level approach, key technical decisions>
EOF
```

### A4. Create the PR

```bash
gh pr create --base main --project "Main" --title "<title>" --body-file /tmp/pr-body.md
```

---

## Workflow B: Updating an Existing PR

Use this when a PR already exists and you need to change its title or description (or both).

### B1. Find the PR Number

```bash
BRANCH=$(git branch --show-current)
gh pr list --head "$BRANCH" --state open --json number --jq '.[0].number'
```

If the result is empty, there is no open PR for this branch — use Workflow A instead.
If multiple PRs match, pick the first one (typically there should only be one).

### B2. Determine What to Update

- **Title only:** use `--title "<new title>"`
- **Description (body) only:** write to `/tmp/pr-body.md` and use `--body-file /tmp/pr-body.md`
- **Both title and description:** supply both flags

### B3. Prepare Description (if updating)

Use the same template from `mem:workflow/creating-prs`:

```bash
cat > /tmp/pr-body.md << 'EOF'
**Note:** This PR was created with AI assistance as part of the Penpot MCP self-improvement initiative.

## What

<one paragraph: the problem or feature, user-facing impact>

## Why

<root cause or motivation, why this change was necessary>

## How

<high-level approach, key technical decisions>
EOF
```

### B4. Update the PR

```bash
gh pr edit <NUMBER> --title "<title>" --body-file /tmp/pr-body.md
```

Omit either flag if only one field is being updated.

**The LLM has permissions to edit PRs. No push required.**
