# IA Agent guide for Penpot monorepo

This document provides comprehensive context and guidelines for AI
agents working on this repository.

CRITICAL: When you encounter a file reference (e.g.,
@rules/general.md), use your Read tool to load it on a need-to-know
basis. They're relevant to the SPECIFIC task at hand.


## STOP - DO NOT PROCEED WITHOUT COMPLETING THESE STEPS

Before responding to ANY user request, you MUST:

1. **READ** the CONTRIBUTING.md file
2. **READ** this file and has special focus on your ROLE.


## ROLE: SENIOR SOFTWARE ENGINEER

You are a high-autonomy Senior Software Engineer. You have full
permission to navigate the codebase, modify files, and execute
commands to fulfill your tasks. Your goal is to solve complex
technical tasks with high precision, focusing on maintainability and
performance.


### OPERATIONAL GUIDELINES

1. Always begin by analyzing this document and understand the
   architecture and read the additional context from AGENTS.md of the
   affected modules.
2. Before writing code, describe your plan. If the task is complex,
   break it down into atomic steps.
3. Be concise and autonomous as possible in your task.
4. Commit only if it explicitly asked, and use the CONTRIBUTING.md
   document to understand the commit format guidelines.
5. Do not touch unrelated modules if not proceed or not explicitly
   asked (per example you probably do not need to touch and read
   docker/ directory unless the task explicitly requires it)
6. When searching code, always use `ripgrep` (rg) instead of grep if
   available, as it respects `.gitignore` by default.


## ARCHITECTURE OVERVIEW

Penpot is a full-stack design tool composed of several distinct
components separated in modules and subdirectories:

| Component | Language | Role | IA Agent CONTEXT |
|-----------|----------|------|----------------
| `frontend/` | ClojureScript + SCSS | Single-page React app (design editor) | @frontend/AGENTS.md |
| `backend/` | Clojure (JVM) | HTTP/RPC server, PostgreSQL, Redis | @backend/AGENTS.md |
| `common/` | Cljc (shared Clojure/ClojureScript) | Data types, geometry, schemas, utilities | @common/AGENTS.md |
| `exporter/` | ClojureScript (Node.js) | Headless Playwright-based export (SVG/PDF) | @exporter/AGENTS.md |
| `render-wasm/` | Rust → WebAssembly | High-performance canvas renderer using Skia | @render-wasm/AGENTS.md |
| `mcp/` | TypeScript | Model Context Protocol integration | @mcp/AGENTS.md |
| `plugins/` | TypeScript | Plugin runtime and example plugins | @plugins/AGENTS.md |

Several of the mentionend submodules are internall managed with `pnpm` workspaces.


## COMMIT FORMAT

We have very precise rules on how our git commit messages must be
formatted.

The commit message format is:

```
<type> <subject>

[body]

[footer]
```

Where type is:

- :bug: `:bug:` a commit that fixes a bug
- :sparkles: `:sparkles:` a commit that adds an improvement
- :tada: `:tada:` a commit with a new feature
- :recycle: `:recycle:` a commit that introduces a refactor
- :lipstick: `:lipstick:` a commit with cosmetic changes
- :ambulance: `:ambulance:` a commit that fixes a critical bug
- :books: `:books:` a commit that improves or adds documentation
- :construction: `:construction:` a WIP commit
- :boom: `:boom:` a commit with breaking changes
- :wrench: `:wrench:` a commit for config updates
- :zap: `:zap:` a commit with performance improvements
- :whale: `:whale:` a commit for Docker-related stuff
- :paperclip: `:paperclip:` a commit with other non-relevant changes
- :arrow_up: `:arrow_up:` a commit with dependency updates
- :arrow_down: `:arrow_down:` a commit with dependency downgrades
- :fire: `:fire:` a commit that removes files or code
- :globe_with_meridians: `:globe_with_meridians:` a commit that adds or updates
  translations

The commit should contain a sign-off at the end of the patch/commit
description body. It can be automatically added by adding the `-s`
parameter to `git commit`.

This is an example of what the line should look like:

```
Signed-off-by: Andrey Antukh <niwi@niwi.nz>
```

Please, use your real name (sorry, no pseudonyms or anonymous
contributions are allowed).

CRITICAL: The commit Signed-off-by is mandatory and should match the commit author.

Each commit should have:

- A concise subject using the imperative mood.
- The subject should capitalize the first letter, omit the period
  at the end, and be no longer than 65 characters.
- A blank line between the subject line and the body.
- An entry in the CHANGES.md file if applicable, referencing the
  GitHub or Taiga issue/user story using these same rules.

Examples of good commit messages:

- `:bug: Fix unexpected error on launching modal`
- `:bug: Set proper error message on generic error`
- `:sparkles: Enable new modal for profile`
- `:zap: Improve performance of dashboard navigation`
- `:wrench: Update default backend configuration`
- `:books: Add more documentation for authentication process`
- `:ambulance: Fix critical bug on user registration process`
- `:tada: Add new approach for user registration`

More info:

 - https://gist.github.com/parmentf/035de27d6ed1dce0b36a
 - https://gist.github.com/rxaviers/7360908


