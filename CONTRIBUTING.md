# Contributing Guide

Thank you for your interest in contributing to Penpot. This guide covers
how to propose changes, submit fixes, and follow project conventions.

For architecture details, module-specific guidelines, and AI-agent
instructions, see [AGENTS.md](AGENTS.md). For final user technical
documentation, see the `docs/` directory or the rendered [Help
Center](https://help.penpot.app/).

## Table of Contents

- [Prerequisites](#prerequisites)
- [Reporting Bugs](#reporting-bugs)
- [Pull Requests](#pull-requests)
- [Commit Guidelines](#commit-guidelines)
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
2. **Discuss before building** — open a question/discussion issue before
   starting work on a new feature or significant change. No PR will be
   accepted without prior discussion, whether it is a new feature, a planned
   one, or a quick win.
3. **Bug fixes** — you may submit a PR directly, but we still recommend
   filing an issue first so we can track it independently of your fix.
4. **Format and lint** — run the checks described in
   [Formatting and Linting](#formatting-and-linting) before submitting.

### Good first issues

We use the `easy fix` label to mark issues appropriate for newcomers.

## Commit Guidelines

Commit messages must follow this format:

```
:emoji: <subject>

[body]

[footer]
```

### Commit types

| Emoji | Description |
|-------|-------------|
| :bug: | Bug fix |
| :sparkles: | Improvement or enhancement |
| :tada: | New feature |
| :recycle: | Refactor |
| :lipstick: | Cosmetic changes |
| :ambulance: | Critical bug fix |
| :books: | Documentation |
| :construction: | Work in progress |
| :boom: | Breaking change |
| :wrench: | Configuration update |
| :zap: | Performance improvement |
| :whale: | Docker-related change |
| :paperclip: | Other non-relevant changes |
| :arrow_up: | Dependency update |
| :arrow_down: | Dependency downgrade |
| :fire: | Removal of code or files |
| :globe_with_meridians: | Add or update translations |
| :rocket: | Epic or highlight |

### Rules

- Use the **imperative mood** in the subject (e.g. "Fix", not "Fixed")
- Capitalize the first letter of the subject
- Add clear and concise description on the body
- Do not end the subject with a period
- Keep the subject to **70 characters** or fewer
- Separate the subject from the body with a **blank line**

### Examples

```
:bug: Fix unexpected error on launching modal
:sparkles: Enable new modal for profile
:zap: Improve performance of dashboard navigation
:ambulance: Fix critical bug on user registration process
:tada: Add new approach for user registration
```

## Formatting and Linting

We use [cljfmt](https://github.com/weavejester/cljfmt) for formatting and
[clj-kondo](https://github.com/clj-kondo/clj-kondo) for linting.

```bash
# Check formatting (does not modify files)
./scripts/check-fmt

# Fix formatting (modifies files in place)
./scripts/fmt

# Lint
./scripts/lint
```

Ideally, run these as git pre-commit hooks.
[Husky](https://typicode.github.io/husky/#/) is a convenient option for
setting this up.

## Changelog

When your change is user-facing or otherwise notable, add an entry to
[CHANGES.md](CHANGES.md) following the same commit-type conventions. Reference
the relevant GitHub issue or Taiga user story.

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
>     right to submit it under the open source license indicated in the file; or
>
> (b) The contribution is based upon previous work that, to the best of my
>     knowledge, is covered under an appropriate open source license and I have
>     the right under that license to submit that work with modifications,
>     whether created in whole or in part by me, under the same open source
>     license (unless I am permitted to submit under a different license), as
>     indicated in the file; or
>
> (c) The contribution was provided directly to me by some other person who
>     certified (a), (b) or (c) and I have not modified it.
>
> (d) I understand and agree that this project and the contribution are public
>     and that a record of the contribution (including all personal information
>     I submit with it, including my sign-off) is maintained indefinitely and
>     may be redistributed consistent with this project or the open source
>     license(s) involved.

### Signed-off-by

All code patches (**documentation is excluded**) must contain a sign-off line
at the end of the commit body. Add it automatically with `git commit -s`.

```
Signed-off-by: Your Real Name <your.email@example.com>
```

- Use your **real name** — pseudonyms and anonymous contributions are not
  allowed.
- The `Signed-off-by` line is **mandatory** and must match the commit author.
