# Backend configuration

- Start at `app.config` for environment coercion and boot validation. For duration settings use hours for whole days (e.g. `168h`, not `7d`). For checks that depend on Clojure assertions, see the startup flag in `mem:backend/testing`; redefining flags at runtime does not restore assertions compiled out at load.
- `app.main` wires `PENPOT_DATABASE_*` into `app.db/create-datasource-config`. Keep driver properties as strings: pgjdbc uses `Properties.getProperty`. A parameter of the same name in `PENPOT_DATABASE_URI` takes precedence; check the URI before diagnosing an ignored setting.
- `prepareThreshold=0` disables server-side prepares, not the driver's cache of parsed queries. A zero query or size limit disables that cache. The configured size is in bytes and rounds up to whole MiB for pgjdbc. Use the schemas and datasource builder for accepted ranges and property names.
