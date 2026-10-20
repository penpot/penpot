---
title: Link previews
desc: How Penpot serves Open Graph metadata for shared links, so that Slack, Discord, Twitter and other platforms render rich previews with the file name and thumbnail.
---

# Link previews

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

The whole feature is gated behind the `link-preview` flag (enabled with
`enable-link-preview` in `PENPOT_FLAGS`), and is **disabled by default**. See
[Security considerations](#security-considerations) below for why.

## How it works, end to end

Penpot routes by query string: the screen and its context travel as
normal query params (`/?screen=workspace&file-id=...`), so the backend
sees everything directly. The feature is therefore built from three
cooperating pieces:

```text
  user shares URL          crawler (Slackbot, ...)            regular browser
        │                          │                                 │
        │  https://host/?screen=workspace&file-id=X                   │
        │                          │                                 │
        ▼                          ▼                                 ▼
   [frontend]                 [nginx]                           [nginx]
   nothing special:     user-agent matches crawler        user-agent is normal
   screen+ids are       rewrite / -> /link-preview              serve SPA index.html
   already in the       (query string preserved)
   query                       │
                               ▼
                          [backend]
                     GET /link-preview?screen=workspace&file-id=X
                     query DB, render Open
                     Graph HTML template
```

### 1. Frontend: nothing to do

File: `frontend/src/app/main/router.cljs`

No URL surgery is needed: navigation writes the query string
directly (`screen` plus the screen params), so when the user copies
the URL from the address bar and shares it, the context ids travel
in a part of the URL that *does* reach the server. The resulting
URLs look like:

```text
https://design.penpot.app/?screen=workspace&team-id=...&file-id=...&page-id=...
https://design.penpot.app/?screen=dashboard-recent&team-id=...&project-id=...
https://design.penpot.app/?screen=auth-login
```

Legacy hash URLs (`#/workspace?...`) from bookmarks and old emails
are translated client-side to the query format on load (one-version
compatibility window, see the query-string routing plan); the
fragment never reaches the server, so this translation can only
happen in the browser.

### 2. Nginx: detecting link preview crawlers

Files: `docker/devenv/files/nginx.conf` (devenv) and
`docker/images/files/nginx.conf.template` (production image).

A `map` block classifies the request by `User-Agent`:

```nginx
map $http_user_agent $penpot_link_preview_agent {
    default 0;
    ~*(slackbot|discordbot|twitterbot|facebookexternalhit|facebookcatalog|whatsapp|telegrambot|linkedinbot|skypeuripreview|pinterestbot|redditbot|embedly|iframely|mastodon|bluesky|applebot|googlebot|bingbot|bingpreview|duckduckbot) 1;
}
```

Inside the SPA root location, crawler requests for `/` are internally
rewritten to the backend link-preview endpoint (the query string is preserved by
`rewrite ... last`):

```nginx
if ($penpot_link_preview_agent) {
    rewrite ^/$ /link-preview last;
}

location = /link-preview {
    proxy_pass http://127.0.0.1:6060/link-preview$is_args$args;   # devenv
    # proxy_pass $PENPOT_BACKEND_URI/link-preview$is_args$args;   # production template
}
```

Regular browsers are not affected: they keep receiving the SPA `index.html`.
No SPA fallback rules are needed for app screens because the app lives
on the single `/` path — the `try_files … /index.html` fallback and the
`^/[^/]+` deep-path rule already cover everything, and no new path
rules will ever be needed for routing. If you self-host behind a
different reverse proxy, you only need to replicate the crawler
rewrite on `/` there.

### 3. Backend: the `/link-preview` endpoint

File: `backend/src/app/http/link_preview.clj` (new namespace).

The handler:

 1. If the `link-preview` flag is not set, skips any lookup and uses the
    default context.
 2. Otherwise parses `file-id` / `project-id` / `team-id` from the query
    params. A present `file-id` is decisive: file links never fall through
    to the project/team card, even when the value is a malformed or unknown
    id (both yield the generic card); only a missing `file-id` key falls
    through to project/team.
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
 5. Renders `backend/resources/app/templates/link-preview.tmpl` and responds with
    `200`, `text/html` and `cache-control: no-store, no-cache, max-age=0`.

The endpoint **always returns 200** with at least the generic metadata; a
non-existent file id, a malformed id or a disabled flag never produce an
error, so crawlers always get a valid preview.

The route is registered in `backend/src/app/http.clj` and wired in the
integrant system map in `backend/src/app/main.clj` (`::http.link-preview/routes`,
which only needs the `::db/pool` dependency). The route declares
`:allowed-methods #{:get :head}`, so other methods get a `405` from the shared
`restrict-methods` middleware.

### The HTML template

File: `backend/resources/app/templates/link-preview.tmpl`.

A minimal HTML page with `og:title`, `og:description`, `og:image`, the
equivalent `twitter:*` card tags and `<meta name="robots" content="noindex">`.
The body contains a single script:

```html
<script>location.replace((location.pathname.replace(/link-preview\/?$/, "") || "/") + location.search);</script>
```

so that if a *human* somehow lands on `/link-preview` (e.g. some clients let users
click through to the fetched URL), the browser bounces back to the SPA root
keeping the query string, and the app loads normally. The
redirect strips only the trailing `link-preview` segment so subpath
deployments keep their prefix. Crawlers do not execute
JavaScript, so they just read the meta tags.

### Making file thumbnails publicly accessible

File: `backend/src/app/http/assets.clj`.

Crawlers fetch `og:image` anonymously, so the thumbnail asset must be served
without authentication. The assets handler decides per storage bucket whether
auth is required; with this feature the `file-thumbnail` bucket is treated as
public **only while the `link-preview` flag is enabled**:

```clojure
(defn- public-bucket?
  [bucket]
  (or (contains? public-buckets bucket)
      (and (= "file-thumbnail" bucket)
           (contains? cf/flags :link-preview))))
```

With the flag disabled, `file-thumbnail` objects keep requiring an
authenticated profile with access to the file, as before.

## The feature flag

Defined in `common/src/app/common/flags.cljc` as `:link-preview`, listed in the
`varia` set and **not** included in the default flags. Enable it on the
backend with:

```bash
export PENPOT_FLAGS="$PENPOT_FLAGS enable-link-preview"
```

It is a backend-only decision point; the frontend URL mirroring is always
active (it is harmless on its own), and the nginx crawler routing is also
unconditional — with the flag off the endpoint simply serves the generic
metadata.

## Security considerations

Enabling `link-preview` deliberately trades some privacy for shareability:

 * **File names become readable by anyone who knows the file id** (the
   `/link-preview` endpoint does no permission check).
 * **Dashboard thumbnails become downloadable by anyone who knows the media
   id** (the `file-thumbnail` bucket becomes public).

Both ids are random UUIDs, so they are not enumerable, but this is
knowledge-of-the-id access, not real authorization. This is the standard
trade-off that link preview features make; it is the reason the flag is off
by default and should be documented to self-hosters before they enable it.

The preview page also sets `robots: noindex` to keep search engines from
indexing these preview pages, and responses are marked non-cacheable.

## Testing it locally (devenv)

1. Make sure the devenv nginx picked up the config (restart the devenv, or
   `nginx -s reload` inside the container, if it predates these changes).

2. The flag already ships enabled in devenv via `backend/scripts/_env`, so
   no export is needed there; outside devenv, enable it before starting
   the backend:

   ```bash
   export PENPOT_FLAGS="$PENPOT_FLAGS enable-link-preview"
   ```

3. In the browser (`http://localhost:3449`), open a file in the workspace and
   go back to the dashboard — leaving the workspace is what generates the
   dashboard thumbnail. Verify the address bar now shows
   `?screen=workspace&file-id=...` (screen plus context, no fragment).

4. Hit the endpoint directly (bypasses the user-agent detection):

   ```bash
   curl "http://localhost:3449/link-preview?file-id=<FILE_ID>"
   ```

   Expect HTML with `og:title` containing the file name and `og:image`
   pointing to `/assets/by-id/<media-id>` (or the default image if the file
   has no thumbnail yet).

5. Simulate a real crawler against the root, exercising the full
   nginx → rewrite → backend path:

   ```bash
   curl -A "Slackbot-LinkExpanding 1.0" "http://localhost:3449/?screen=workspace&file-id=<FILE_ID>"
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

 * `backend/test/backend_tests/http_link_preview_test.clj` — endpoint behavior:
   default context, file with/without thumbnail, non-existent and malformed
   file ids, deleted file, only-deleted thumbnail, latest-thumbnail revision
   ordering, file-name HTML escaping, response headers, file-beats-project
   priority, decisive file-id (malformed vs absent with a project id),
   project and team links, and flag disabled.
 * `backend/test/backend_tests/http_assets_test.clj`
   (`objects-handler-file-thumbnail-bucket-link-preview-flag`) — the
   `file-thumbnail` bucket is public only while the flag is enabled.
   * `frontend/test/frontend_tests/router_test.cljs` — the `screen`
     match/resolve rules (token building, missing/unknown screen,
     repeated keys).

## Relevant files

| File | Role |
|---|---|
| `backend/src/app/http/link_preview.clj` | `/link-preview` handler: flag check, DB lookup, template rendering |
| `backend/resources/app/templates/link-preview.tmpl` | Open Graph HTML template + human redirect script |
| `backend/src/app/http/assets.clj` | Makes `file-thumbnail` bucket public under the flag |
| `backend/src/app/http.clj`, `backend/src/app/main.clj` | Route registration and system wiring |
| `common/src/app/common/flags.cljc` | `:link-preview` flag definition |
| `frontend/src/app/main/router.cljs` | Screen match/resolve over the query string |
| `frontend/src/app/main/ui/routes.cljs` | Route table plus the one-version legacy hash translation |
| `docker/devenv/files/nginx.conf` | Devenv crawler detection and `/link-preview` routing |
| `docker/images/files/nginx.conf.template` | Same routing for the production image |
