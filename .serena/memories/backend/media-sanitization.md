# Backend Media Sanitization (SVG)

Uploading and importing an SVG both run it through `app.media/sanitize-svg`,
the single dispatch point:

- without `:remote-media-processing`, `app.media.local/process :sanitize-svg`
  runs the ad-hoc filter `app.media.svg/sanitize-svg` (blocklist: `script`,
  `foreignObject`, `set`, `animate*`, `on*` attributes, `javascript:` hrefs);
- with `:remote-media-processing`, `app.media.remote/process :sanitize-svg`
  posts the SVG to media-processor (`POST /api/svg/sanitize`), where DOMPurify
  sanitizes it with an allowlist.

Call sites:

- upload: `app.rpc.commands.media/process-main-image` (writes the cleaned SVG to
  a tempfile before `sto/put-object!`);
- binfile import: `app.binfile.common/sanitize-imported-svg` (returns the cleaned
  `:bytes` with their `:size` and blake2b `:hash`), called from v3
  `import-storage-objects`, v2 `read-storage-object!` and v1
  `read-section :v1/sobjects`.

Rules:

- Fail closed: when the remote backend is enabled but the service fails, the
  exception propagates and the upload/import fails; there is no silent fallback
  to the weaker local filter.
- Text in, text out: the sanitizer takes and returns the SVG as a UTF-8 string.
- `:app.tasks.import-binfile/job-def` carries `::http.client/client` and
  `::setup/shared-keys` (the RPC cfg already does), so the import job — not only
  the RPC path — can reach media-processor.
- The two modes do not produce identical output: the local filter re-serializes
  with `clojure.xml`, DOMPurify uses its own serializer and a different
  allowlist (e.g. it also drops `<use>`). Do not assert byte equality across
  modes.
- Scope: only sanitization is delegated. SVG info extraction
  (`app.media.svg/parse-svg`, `get-basic-info-from-svg`) stays in the JVM.

Service endpoint, its DOMPurify config and its per-call jsdom window:
`mem:media-processor/core`.
