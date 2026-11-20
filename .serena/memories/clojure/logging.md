# Clojure Logging Conventions

Structured logging through `app.common.logging` (`l/trc`/`l/dbg`/`l/info`/`l/wrn`/`l/error`/`l/fatal`): `:hint` carries the human-readable message, every other datum goes in its own keyword field (`:id`, `:container`, `:removed`, ...).

- `:hint` values start lowercase: `"fix dangling children"`, not `"Fix dangling children"`. Uppercase-first hints exist as legacy (~57 occurrences, the densest in `backend/src`; also `l/trc` in migration `legacy-28` and `log/error` in `app.common.files.repair`) — do not copy them, and do not mass-rewrite them unless the surrounding code needs the change.
- A name that requires uppercase (an env var like `PENPOT_MEDIA_PROCESSING_SERVICE_URI`, an acronym like `MCP` or `LDAP`) stays uppercase inside an otherwise lowercase sentence.
- Repair work done by a file migration logs at `l/trc`, only when it changes something, and with the ids it touched: the house style of `migrations.cljc` (`legacy-28` precedes; the file has no `warn`/`info` lines). The shipped `log4j2.xml` pins the `app` logger to `info` (static file, `monitorInterval="60"`), so those trace lines appear only after the operator raises the level; the durable record of a repaired file is the migration id in its `:migrations`.
