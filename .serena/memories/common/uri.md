# Common URI helpers

`app.common.uri` wraps `lambdaisland.uri` and re-exports `join`, `parse`, `query-encode`, `percent-encode` and `uri?` with `dm/export`.

## `join` resolves references, it does NOT concatenate paths

- `lambdaisland.uri/join` implements RFC 3986 reference resolution, not path concatenation: a relative reference that does not start with `/` replaces everything after the last `/` of the path it is resolved against. Joining several segments resolves them successively, so each segment replaces the last segment of the previous result.
- `(join "https://h/" "api/teams/" "<id>" "users/" "<pid>")` returns `https://h/api/teams/users/<pid>`, not `.../api/teams/<id>/users/<pid>`: `"users/"` replaced `"<id>"`. `(apply join base segments)` behaves the same.
- `ensure-path-slash` normalizes only the BASE to end with `/`; it does not touch the segments, so a base ending in `/` is not enough for a multi-segment append.
- To append several segments: either join them into a single relative path first (dropping empty segments and collapsing slashes), or make every non-final segment end with `/`, including the dynamic ids. Keep that normalization in the shared URL builder, not in each call site.
- A segment starting with `/` resolves against the host root and silently drops the base subpath; reject it.
- Frontend `app.config` normalizes the base (`normalize-uri`) and relies on callers passing trailing-slash segments by convention (`resolve-file-media` joins `"assets/by-file-media-id/"` then `"<id>/thumbnail"`); it does not normalize segments.
- Backend shared builders are `app.config/join-uri` and `app.config/get-public-uri`. A past refactor moved Nitrate's per-request helper onto them without the segment normalization and broke every multi-segment Nitrate URL that passes `"api/teams/" team-id "users/" profile-id`.
