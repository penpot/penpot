# Contributing Guide

Thank you for your interest in contributing to Penpot. This guide covers
how to propose changes, submit fixes, and follow project conventions.

For architecture details, module-specific guidelines, and AI-agent
instructions, see [AGENTS.md](AGENTS.md). For final user technical
documentation, see the `docs/` directory or the rendered [Help
Center](https://help.penpot.app/).

## Table of Contents

- [Prerequisites](#prerequisites)
- [AI-Assisted Contributions](#ai-assisted-contributions)
- [Reporting Bugs](#reporting-bugs)
- [Pull Requests](#pull-requests)
  - [Workflow](#workflow)
  - [Branch naming](#branch-naming)
  - [Format](#format)
    - [Title format](#title)
    - [Description](#description)
  - [Review process](#review-process)
  - [What we won't accept](#what-we-wont-accept)
  - [Good first issues](#good-first-issues)
- [Commit Guidelines](#commit-guidelines)
  - [Commit types](#commit-types)
  - [Rules](#rules)
  - [Examples](#examples)
- [Formatting and Linting](#formatting-and-linting)
- [Changelog](#changelog)
- [Code of Conduct](#code-of-conduct)
- [Developer's Certificate of Origin (DCO)](#developers-certificate-of-origin-dco)

## Prerequisites

- **Language**: Penpot is written primarily in Clojure (backend), ClojureScript
  (frontend/exporter), and Rust (render-wasm). Familiarity with the Clojure
  ecosystem is expected for most contributions.
- **Issue tracker**: We use [GitHub Issues](https://github.com/penpot/penpot/issues)
  for public bugs and [Taiga](https://tree.taiga.io/project/penpot/) for
  internal project management. Changelog entries reference both.
- **AI coding agents**: guidance lives in `AGENTS.md` and the skills in
  `.agents/skills/`, which Codex, opencode, Cursor, Zed, Amp, omp and pi read
  without setup. Claude Code reads `AGENTS.md` from 2.1.277, but only in a
  project that has no instruction file of its own, so keep no `CLAUDE.md`,
  `.claude/CLAUDE.md` or `CLAUDE.local.md` in your checkout: any one of them
  hides this repository's guidance from your session. Skills still load from
  `.claude/skills` alone, so link it once per clone with
  `mkdir -p .claude && ln -s ../.agents/skills .claude/skills`, or with
  `npx skills add ./.agents/skills --agent claude-code`. Below 2.1.277, and
  on Bedrock, Vertex and Foundry where the fallback has not arrived, add
  `ln -s AGENTS.md CLAUDE.md` and remove it once your client has the
  feature. Personal instructions belong in `AGENTS.local.md`, personal
  skills in `.agents/local/skills/`, and personal Claude steering in
  `.claude/rules/*.md`, which Claude loads beside the project instructions
  without switching the fallback off. Every one of these paths is
  gitignored.

## AI-Assisted Contributions

We support the responsible use of AI tools in the development process. However, all contributions to Penpot - including issues, pull requests, and any other submissions - must meet a reasonable standard of quality, accuracy, and human oversight.

If AI-assisted content is used, it must be carefully reviewed and verified by a human before submission. Contributions that don't meet these standards may be rejected or closed without detailed review or a reply.

## Reporting Bugs

Report bugs via [GitHub Issues](https://github.com/penpot/penpot/issues).
Before filing, search existing issues to avoid duplicates.

Include the following when possible:

1. Steps to reproduce the error.
2. Browser and browser version used.
3. DevTools console exception stack trace (if available).

For security bugs or issues better discussed in private, email
`support@penpot.app` or report them on [Github Security
Advisories](https://github.com/penpot/penpot/security/advisories)

> **Note:** We do not have a formal bug bounty program. Security
> contributions are recognized in the changelog.

## Pull Requests

### Workflow

1. **Read the DCO** — see [Developer's Certificate of Origin](#developers-certificate-of-origin-dco)
   below. All code patches must include a `Signed-off-by` line.
2. **Discuss before building** — open a [GitHub
   Issue](https://github.com/penpot/penpot/issues) before starting work on
   a new feature or significant change. For planned features on the roadmap,
   reference the corresponding Taiga story. Do not expect your contribution
   to be accepted if you submit it without prior discussion — this applies
   to new features, planned features, and quick wins alike.
3. **Bug fixes** — you may submit a PR directly, but we still recommend
   filing an issue first so we can track it independently of your fix.
4. **Format and lint** — run the checks described in
   [Formatting and Linting](#formatting-and-linting) before submitting.

### Branch naming

Branch names are not enforced, but we recommend the following:

- **`issue-NNNN`** — when working from a GitHub issue, name the branch after
  it (e.g. `issue-11525`). This makes each PR's origin self-evident.
- Otherwise, use a short, descriptive name with words separated by hyphens
  and no slashes (e.g. `fix-ellipse-icon-typo`, `feat-auto-link-libraries`).

Since PRs are squash-merged, the branch name does not survive into the
commit history — what matters is the [PR title](#title).

### Format

#### Title

> **IMPORTANT:** When a PR is squash-merged, the PR title becomes the
> commit message on the main branch. Getting the title right matters.

Pull request titles **must** follow the same convention as commit subjects:

```
:emoji: Subject line (imperative, capitalized, no period, <=70 chars)
```

Read [Creating Commits](./.serena/memories/workflow/creating-commits.md)
for more concrete information.

#### Description

Every pull request should include a description that helps reviewers
understand the change quickly:

1. **What and why** — describe the change and its motivation.
2. **Link related issues** — use `Closes #1234` or reference a Taiga
   story (e.g. `Taiga #5678`).
3. **Screenshots or recordings** — required for any UI-visible change.
4. **Testing notes** — how did you verify the change? Any edge cases?
5. **Breaking changes** — call out anything that affects existing users
   or requires migration steps.

Read [Creating PRs](./.serena/memories/workflow/creating-prs.md)
for more concrete information.

### Review process

- We are a small team and maintainers juggle reviews alongside other
  tasks. Please do not expect your code to be reviewed instantly.
- Reviews are handled in dedicated blocks of time, usually in the order
  PRs arrive. It may take a few days to get a first review, especially
  when urgent tasks come up.
- Address review feedback by **pushing new commits** — do not
  force-push during review, as it breaks comment threads.
- PRs require at least **one approval** before merge.
- We use **squash-merge** by default. The PR title becomes the final
  commit message, so follow the [title format](#title) above.

### What we won't accept

To save time on both sides, please avoid submitting PRs that:

- Introduce new dependencies without prior discussion.
- Change the build system or CI configuration without maintainer approval.
- Mix unrelated changes in a single PR — keep PRs focused on one concern.
- Submit AI-generated code without human review.
- Skip local syntax and formatting checks before submitting.
- Skip the [discussion step](#workflow) for non-bug-fix changes.

### Good first issues

We use the `good first issue` label to mark issues appropriate for newcomers.

## Commit Guidelines

Commit messages must follow this format:

```
:emoji: <subject>

[body]

[footer]
```

### Commit types

| Emoji                  | Code                     | Description                |
| ---------------------- | ------------------------ | -------------------------- |
| :bug:                  | `:bug:`                  | Bug fix                    |
| :sparkles:             | `:sparkles:`             | Improvement or enhancement |
| :tada:                 | `:tada:`                 | New feature                |
| :recycle:              | `:recycle:`              | Refactor                   |
| :lipstick:             | `:lipstick:`             | Cosmetic changes           |
| :ambulance:            | `:ambulance:`            | Critical bug fix           |
| :books:                | `:books:`                | Documentation              |
| :construction:         | `:construction:`         | Work in progress           |
| :boom:                 | `:boom:`                 | Breaking change            |
| :wrench:               | `:wrench:`               | Configuration update       |
| :zap:                  | `:zap:`                  | Performance improvement    |
| :whale:                | `:whale:`                | Docker-related change      |
| :paperclip:            | `:paperclip:`            | Other non-relevant changes |
| :arrow_up:             | `:arrow_up:`             | Dependency update          |
| :arrow_down:           | `:arrow_down:`           | Dependency downgrade       |
| :fire:                 | `:fire:`                 | Removal of code or files   |
| :globe_with_meridians: | `:globe_with_meridians:` | Add or update translations |
| :rocket:               | `:rocket:`               | Epic or highlight          |

### Rules

- Use the **imperative mood** in the subject (e.g. "Fix", not "Fixed")
- Capitalize the first letter of the subject
- Add clear and concise description on the body
- Do not end the subject with a period
- Keep the subject to **70 characters** or fewer
- **Wrap body lines at 76 characters or fewer** (trailers and URLs excepted)
- Separate the subject from the body with a **blank line**

You can check a commit against these rules with `./scripts/check-commit`.

### Examples

```
:bug: Fix unexpected error on launching modal
:sparkles: Enable new modal for profile
:zap: Improve performance of dashboard navigation
:ambulance: Fix critical bug on user registration process
:tada: Add new approach for user registration
```

## Formatting and Linting

Each module has its own linting and formatting commands — see the relevant one on the
[Serena Memories](./.serena/memories/)

## Changelog

The changelog is updated automatically as part of the release process. Contributors
should **not** modify `CHANGES.md` manually in their pull requests.

## Code of Conduct

This project follows the [Contributor Covenant](https://www.contributor-covenant.org/).
The full Code of Conduct is available at
[help.penpot.app/contributing-guide/coc](https://help.penpot.app/contributing-guide/coc/)
and in the repository's [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

To report unacceptable behavior, open an issue or contact a project maintainer
directly.

## Developer's Certificate of Origin (DCO)

By submitting code you agree to and can certify the following:

> **Developer's Certificate of Origin 1.1**
>
> By making a contribution to this project, I certify that:
>
> (a) The contribution was created in whole or in part by me and I have the
> right to submit it under the open source license indicated in the file; or
>
> (b) The contribution is based upon previous work that, to the best of my
> knowledge, is covered under an appropriate open source license and I have
> the right under that license to submit that work with modifications,
> whether created in whole or in part by me, under the same open source
> license (unless I am permitted to submit under a different license), as
> indicated in the file; or
>
> (c) The contribution was provided directly to me by some other person who
> certified (a), (b) or (c) and I have not modified it.
>
> (d) I understand and agree that this project and the contribution are public
> and that a record of the contribution (including all personal information
> I submit with it, including my sign-off) is maintained indefinitely and
> may be redistributed consistent with this project or the open source
> license(s) involved.

### Signed-off-by

All code patches (**documentation is excluded**) must contain a sign-off line
at the end of the commit body. Add it automatically with `git commit -s`.

```
Signed-off-by: Your Real Name <your.email@example.com>
```

- Use your **real name** — pseudonyms and anonymous contributions are not
  allowed.
- The `Signed-off-by` line is **mandatory** and must match the commit author.
