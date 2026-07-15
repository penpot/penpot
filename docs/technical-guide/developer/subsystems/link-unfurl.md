---
title: Link unfurl (link previews)
desc: How Penpot serves Open Graph metadata for shared links, so that Slack, Discord, Twitter and other platforms render rich previews with the file name and thumbnail.
---

# Link unfurl (link previews)

When a user pastes a Penpot link in a chat or social platform (Slack, Discord,
Twitter/X, WhatsApp, Telegram, LinkedIn, Mastodon, Bluesky...), the platform's
crawler fetches the URL and looks for [Open Graph](https://ogp.me/) metadata to
render a rich preview card. This subsystem serves that metadata dynamically:

 * For a **file** link: the file name as title and the latest dashboard
   thumbnail of the file as preview image.
 * For a **project** or **team** link: a generic "Project | Penpot" or
   "Team dashboard | Penpot" title.
 * In any other case (or when the feature is disabled): the default Penpot
   title, description and preview image.

The whole feature is gated behind the `link-unfurl` flag (enabled with
`enable-link-unfurl` in `PENPOT_FLAGS`), and is **disabled by default**. See
[Security considerations](#security-considerations) below for why.

## How it works, end to end

The main obstacle is that Penpot is a SPA and all the routing state lives in
the URL **fragment** (`#/workspace?file-id=...`). The fragment is never sent to
the server, so with a plain URL the backend has no way to know which file the
link points to. The feature is therefore built from three cooperating pieces:

```text
 user shares URL          crawler (Slackbot, ...)            regular browser
       │                          │                                 │
       │  https://host/?file-id=X#/workspace?...                    │
       │                          │                                 │
       ▼                          ▼                                 ▼
   [frontend]                 [nginx]                           [nginx]
 mirrors context      user-agent matches crawler        user-agent is normal
 params before the    rewrite / -> /unfurl              serve SPA index.html
 fragment on every    (query string preserved)
 navigation                       │
                                  ▼
                             [backend]
                        GET /unfurl?file-id=X
                        query DB, render Open
                        Graph HTML template
```

### 1. Frontend: mirroring context params on the query string

File: `frontend/src/app/main/router.cljs`

On every navigation, the `navigated` event calls `match->context-params` to
extract the identifiers that give sharing context to the current route, and
mirrors them on the query string (before the fragment) using
`history.replaceState`. The resulting URLs look like:

```text
https://design.penpot.app/?file-id=<uuid>#/workspace?team-id=...&file-id=...&page-id=...
https://design.penpot.app/?team-id=<uuid>&project-id=<uuid>#/dashboard/recent?...
https://design.penpot.app/?team-id=<uuid>#/dashboard/recent?team-id=...
```

`match->context-params` implements a priority: if the route has a `file-id`
only that is mirrored; otherwise `project-id` (together with its `team-id`);
otherwise `team-id`. Routes without any of those ids (e.g. auth pages) mirror
nothing and `replaceState` strips any stale query string. Ids are read both
from `:query-params` (current routes) and from `[:params :path]` (legacy
routes that carry them as path params).

This way, when the user copies the URL from the address bar and shares it, the
context ids travel in a part of the URL that *does* reach the server.

### 2. Nginx: detecting link preview crawlers

Files: `docker/devenv/files/nginx.conf` (devenv) and
`docker/images/files/nginx.conf.template` (production image).

A `map` block classifies the request by `User-Agent`:

```nginx
map $http_user_agent $penpot_unfurl_agent {
    default 0;
    ~*(slackbot|discordbot|twitterbot|facebookexternalhit|facebookcatalog|whatsapp|telegrambot|linkedinbot|skypeuripreview|pinterestbot|redditbot|embedly|iframely|mastodon|bluesky) 1;
}
```

Inside the SPA root location, crawler requests for `/` are internally
rewritten to the backend unfurl endpoint (the query string is preserved by
`rewrite ... last`):

```nginx
if ($penpot_unfurl_agent) {
    rewrite ^/$ /unfurl last;
}

location = /unfurl {
    proxy_pass http://127.0.0.1:6060/unfurl$is_args$args;   # devenv
    # proxy_pass $PENPOT_BACKEND_URI/unfurl$is_args$args;   # production template
}
```

Regular browsers are not affected: they keep receiving the SPA `index.html`.
If you self-host behind a different reverse proxy, you need to replicate this
routing there.

### 3. Backend: the `/unfurl` endpoint

File: `backend/src/app/http/unfurl.clj` (new namespace).

The handler:

 1. If the `link-unfurl` flag is not set, skips any lookup and uses the
    default context.
 2. Otherwise parses `file-id` / `project-id` / `team-id` from the query
    params (invalid UUIDs are tolerated and treated as absent).
 3. For a `file-id`, runs a single query joining `file` with its most recent
    non-deleted `file_thumbnail` row (the dashboard thumbnail):

    ```sql
    SELECT f.name, ft.media_id
      FROM file AS f
      LEFT JOIN file_thumbnail AS ft
             ON (ft.file_id = f.id AND ft.deleted_at IS NULL)
     WHERE f.id = ?
       AND f.deleted_at IS NULL
     ORDER BY ft.revn DESC NULLS LAST
     LIMIT 1
    ```

 4. Builds the context: `:title` is `"<file name> | Penpot"` and `:image` is
    `<public-uri>/assets/by-id/<media-id>` when a thumbnail exists. Missing
    data falls back to the defaults; the default image is
    `<public-uri>/images/penpot-link-preview.png` (a static asset shipped in
    `frontend/resources/public/images/`).
 5. Renders `backend/resources/app/templates/unfurl.tmpl` and responds with
    `200`, `text/html` and `cache-control: no-store, no-cache, max-age=0`.

The endpoint **always returns 200** with at least the generic metadata; a
non-existent file id, a malformed id or a disabled flag never produce an
error, so crawlers always get a valid preview.

The route is registered in `backend/src/app/http.clj` and wired in the
integrant system map in `backend/src/app/main.clj` (`::http.unfurl/routes`,
which only needs the `::db/pool` dependency).

### The HTML template

File: `backend/resources/app/templates/unfurl.tmpl`.

A minimal HTML page with `og:title`, `og:description`, `og:image`, the
equivalent `twitter:*` card tags and `<meta name="robots" content="noindex">`.
The body contains a single script:

```html
<script>location.replace("/" + location.hash);</script>
```

so that if a *human* somehow lands on `/unfurl` (e.g. some clients let users
click through to the fetched URL), the browser bounces back to the SPA root
keeping the fragment, and the app loads normally. Crawlers do not execute
JavaScript, so they just read the meta tags.

### Making file thumbnails publicly accessible

File: `backend/src/app/http/assets.clj`.

Crawlers fetch `og:image` anonymously, so the thumbnail asset must be served
without authentication. The assets handler decides per storage bucket whether
auth is required; with this feature the `file-thumbnail` bucket is treated as
public **only while the `link-unfurl` flag is enabled**:

```clojure
(defn- public-bucket?
  [bucket]
  (or (contains? public-buckets bucket)
      (and (= "file-thumbnail" bucket)
           (contains? cf/flags :link-unfurl))))
```

With the flag disabled, `file-thumbnail` objects keep requiring an
authenticated profile with access to the file, as before.

## The feature flag

Defined in `common/src/app/common/flags.cljc` as `:link-unfurl`, listed in the
`varia` set and **not** included in the default flags. Enable it on the
backend with:

```bash
export PENPOT_FLAGS="$PENPOT_FLAGS enable-link-unfurl"
```

It is a backend-only decision point; the frontend URL mirroring is always
active (it is harmless on its own), and the nginx crawler routing is also
unconditional — with the flag off the endpoint simply serves the generic
metadata.

## Security considerations

Enabling `link-unfurl` deliberately trades some privacy for shareability:

 * **File names become readable by anyone who knows the file id** (the
   `/unfurl` endpoint does no permission check).
 * **Dashboard thumbnails become downloadable by anyone who knows the media
   id** (the `file-thumbnail` bucket becomes public).

Both ids are random UUIDs, so they are not enumerable, but this is
knowledge-of-the-id access, not real authorization. This is the standard
trade-off that link preview features make; it is the reason the flag is off
by default and should be documented to self-hosters before they enable it.

The unfurl page also sets `robots: noindex` to keep search engines from
indexing these preview pages, and responses are marked non-cacheable.

## Testing it locally (devenv)

1. Make sure the devenv nginx picked up the config (restart the devenv, or
   `nginx -s reload` inside the container, if it predates these changes).

2. Enable the flag before starting the backend REPL:

   ```bash
   export PENPOT_FLAGS="$PENPOT_FLAGS enable-link-unfurl"
   ```

3. In the browser (`http://localhost:3449`), open a file in the workspace and
   go back to the dashboard — leaving the workspace is what generates the
   dashboard thumbnail. Verify the address bar now shows `?file-id=...`
   before the `#`.

4. Hit the endpoint directly (bypasses the user-agent detection):

   ```bash
   curl "http://localhost:3449/unfurl?file-id=<FILE_ID>"
   ```

   Expect HTML with `og:title` containing the file name and `og:image`
   pointing to `/assets/by-id/<media-id>` (or the default image if the file
   has no thumbnail yet).

5. Simulate a real crawler against the root, exercising the full
   nginx → rewrite → backend path:

   ```bash
   curl -A "Slackbot-LinkExpanding 1.0" "http://localhost:3449/?file-id=<FILE_ID>"
   ```

   The same URL with a normal user-agent must return the SPA `index.html`.

6. Verify the thumbnail is public:

   ```bash
   curl -I "http://localhost:3449/assets/by-id/<MEDIA_ID>"
   ```

   Expect `200` without any session cookie while the flag is on, and `401`
   with the flag off (restart the backend after changing flags).

7. To see the actual preview card rendered by Slack/Discord you need a
   publicly reachable URL (`og:image` is built from `PENPOT_PUBLIC_URI`), so
   use a tunnel such as ngrok; for local verification the `curl` checks above
   are enough.

## Automated tests

 * `backend/test/backend_tests/http_unfurl_test.clj` — endpoint behavior:
   default context, file with/without thumbnail, non-existent and malformed
   file ids, project and team links, and flag disabled.
 * `backend/test/backend_tests/http_assets_test.clj`
   (`objects-handler-file-thumbnail-bucket-link-unfurl-flag`) — the
   `file-thumbnail` bucket is public only while the flag is enabled.
 * `frontend/test/frontend_tests/router_test.cljs` — `match->context-params`
   priority and legacy path-params support.

## Relevant files

| File | Role |
|---|---|
| `backend/src/app/http/unfurl.clj` | `/unfurl` handler: flag check, DB lookup, template rendering |
| `backend/resources/app/templates/unfurl.tmpl` | Open Graph HTML template + human redirect script |
| `backend/src/app/http/assets.clj` | Makes `file-thumbnail` bucket public under the flag |
| `backend/src/app/http.clj`, `backend/src/app/main.clj` | Route registration and system wiring |
| `common/src/app/common/flags.cljc` | `:link-unfurl` flag definition |
| `frontend/src/app/main/router.cljs` | Mirrors context ids on the query string on navigation |
| `docker/devenv/files/nginx.conf` | Devenv crawler detection and `/unfurl` routing |
| `docker/images/files/nginx.conf.template` | Same routing for the production image |
