# Common URI helpers

- `app.common.uri/join` resolves each argument as a URI reference, not a path segment. `(join "https://h/" "api/teams/" "id" "users/" "profile")` produces `https://h/api/teams/users/profile`: `"users/"` replaces `"id"`. A leading slash drops the entire configured base subpath.
- `ensure-path-slash` normalizes only the base. Pass one relative path or make every non-final argument end in `/`, including dynamic IDs. See backend `app.config/join-uri` and the multi-segment calls in `app.nitrate`; frontend `app.config` relies on the same reference-resolution rule.
