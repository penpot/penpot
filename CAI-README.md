# Cocoon AI base fork of penpot/penpot

This is the **base fork** of [penpot/penpot](https://github.com/penpot/penpot)
per [`cai-portal` ADR 0004](https://github.com/Cocoon-AI/cai-portal/blob/main/docs/adrs/0004-foreign-app-pattern.md)
Phase 5a + D15.

> See [`README.md`](./README.md) for the upstream Penpot project.
> This file documents the **Cocoon AI overlay** only.

## What this repo is

- A real git fork of `penpot/penpot`, tracked at the ref pinned in
  [`app-manifest.yaml`](./app-manifest.yaml) (`upstream.tracking_ref`).
- Carries **only platform integration** on top of upstream:
  - [`Dockerfile`](./Dockerfile) per ADR 0003 (distroless `:nonroot`,
    multi-stage, OTel javaagent wiring).
  - [`app-manifest.yaml`](./app-manifest.yaml) — `kind: foreign`,
    schema_version 2.
  - [`.github/workflows/cai-deploy.yml`](./.github/workflows/cai-deploy.yml)
    + [`.github/workflows/cai-upstream-track.yml`](./.github/workflows/cai-upstream-track.yml).
  - [`.trivyignore`](./.trivyignore) (allowlist-by-exception baseline).
  - [`BACKUP.md`](./BACKUP.md).
- **No Cocoon-specific business logic.** Tenant forks
  (`Cocoon-AI/cai-penpot`, future `Cocoon-AI/<tenant>-penpot`) carry
  that.
- **Not directly deployed.** Tenant forks are the deploy targets.

## Branches

- `develop` — upstream's default; tracks `penpot/penpot:develop`.
- `main` — Cocoon AI base-fork overlay. Carries the platform-
  integration commits.

`cai-upstream-track.yml` merges upstream tags into this `main` and
opens a PR (clean or with conflicts).

## Lineage

```
penpot/penpot (upstream)
  ↓ rebased on cadence via cai-upstream-track.yml
Cocoon-AI/penpot (this repo — base fork)
  ↓ rebased on cadence via cai-upstream-track.yml in the tenant fork
Cocoon-AI/cai-penpot (Cocoon tenant fork — the deploy target)
```

External tenant forks (future) would sit alongside `cai-penpot`,
each as their own fork of this base.

## Daily ops

This repo isn't directly deployed. Operations happen on the tenant
forks. See `cai-portal/docs/runbooks/foreign-app-deploy.md`.

For maintainers of this base fork:

| Task | Command |
|---|---|
| Rebase on upstream | `cai foreign upgrade` |
| Build CI image (verify Dockerfile still works) | push to `main` |

## TODOs visible in this scaffold

The Dockerfile + Terraform carry `TODO(0004-phase-5a)` markers for
the bits that need real values:
- Penpot's backend build command (depends on upstream's structure;
  validates against `upstream.tracking_ref`).
- The OTel javaagent version + COPY path.
- The Terraform module path + remote-state backend config.

These are gated by Phase 5b actually deploying Penpot — when that
work happens, the TODOs get resolved and CI is exercised end-to-end.
