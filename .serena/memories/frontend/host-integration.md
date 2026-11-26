# External host integration

- The SaaS host can inject `globalThis` hooks consumed by `app.config`. Treat them as an external API: absence of in-repo callers is not evidence that a hook is dead. In particular, preserve `external-feature-flag` as the A/B test seam.
- `frontend/resources/templates/index.mustache` loads the callback registry stub before `config.js` so the host can register named callbacks during config evaluation. The CLJS fallback in `app.config` must preserve a registry or registration function already supplied by the host or stub.
- `app.main.data.event` forwards audit events after `make-event` via the named callbacks. Keep this boundary vendor-agnostic; don't add a vendor SDK to Penpot to deliver a host event.
