# Memory Maintenance

## Discovery

- Start at `mem:critical-info`, then follow the relevant section's `core` and focused references. Group notes by project module or topic.
- Use backticked `mem:` references. The caller explains when to read the destination and why (e.g. failed shadow-cljs build or stale hot reload → `mem:frontend/compile-diagnostics`). Don't repeat that routing rule inside the destination.

## Content and format

- Keep stable, non-obvious guidance: decisions and reasons, cross-file invariants, ordering constraints, and environment or tool traps. Point to code or docs for details already evident there, including comments.
- Prefer terse bullets. Skip generic advice, one-off task notes, line-level facts and examples that don't prevent a likely mistake.
- No manual line wrapping: one line per bullet or paragraph. When editing a hard-wrapped memory, unwrap the whole file.

## Maintenance

- Serena's memory rename tool updates references. After moving or deleting a note, check incoming links and run `serena memories check` when available.
