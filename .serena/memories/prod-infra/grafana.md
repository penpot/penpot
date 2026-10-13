# Grafana

Private instance with Penpot metrics and logs. Dashboards are edited through the Grafana API (MCP tools); there is no dashboards-as-code for them.

Keep this memory free of internal names: no host names, no deployed versions, no topology. The datasource ids and the label conventions are the useful technical part.

## Datasources

- Metrics — **Prometheus**, uid `xkRmyFBGk`. PromQL. Metric families: `penpot_*` (RPC, task timings, CLIMIT, HTTP, websocket, storage, and the application log events of the backend), `hikaricp_*`, `jvm_*` and `process_*` on the backend (`job="app"`); `pg_*` from the Postgres exporter (`job="postgres"`); `redis_*` from the Redis exporter (`job="redis"`); `node_*` and `container_*` from node-exporter and cAdvisor. Backend histograms are in **milliseconds**.
- Logs — **Loki**, uid `h3CGKhEMk`. LogQL, and filter by the structured metadata `logger`/`level` (`{job="penpot"} | logger="..." | level="ERROR"`). Two things to remember when counting from it: the CLIMIT rejections have their own counters (`penpot_rpc_climit_rejected_total`, by `name` and `reason`) and their `hint="rejected"` line goes out at WARN only when something is left queued behind the call (a lone caller that times out is written at TRACE), so counting lines is not counting rejections -- the same goes for `hint="enqueued"` and `hint="acquired"`; and the only signal that exists nowhere else is the profile behind a queue (`logger="app.rpc.climit"`, whose `id` carries the key), chased there on demand.
- SQL — **Postgres**, uid `y8ea9tfMk`. Panels need `rawQuery: true`, `editorMode: "code"` and `format: "table"`. The `task` table is the only truth about the queue depth. It is a **read-only replica**: it rejects any write, `CREATE TEMP TABLE` included, so queries through it must be plain `SELECT`s. It also cuts long-running queries (the MCP path has a short timeout), so bound them (e.g. a list of ids); an empty answer there is not proof that there is no data.
- **Postgres (telemetry)**, uid `9YAUuM_Gz`.
- `$__rate_interval` and `$__interval` are substituted by Grafana; use them instead of fixed windows in graphs.

## Main dashboard

- `Production status`, uid `penpot-production-status`. Sections: general state and deploy, `RPC y CLIMIT`, PostgreSQL and pool, Redis/msgbus, tasks, JVM/process. Variables `$env` and `$host` (custom), no auto refresh. Every section opens collapsed except `RPC y CLIMIT`, which is the one read on a normal day.
- Per-host investigation dashboards are separate, and the alert rules deeplink into them.

## Conventions

- Filter with `instance=~"$host.$env.<domain>.*"`. To know which instances serve, count the ones reporting JVM metrics; `up{job="app"}` is not reliable (it can report 1 while the endpoint answers an empty body).
- Layout: a band of number-only panels at the top of each section, wider data panels below, tables tall enough to show every row, legend on the **right** sorted by last value descending (`sortBy: "Last *"`, `sortDesc: true`).
- Panels comparing used against total need `defaults.min = 0`, else the automatic axis exaggerates the difference.
- Counters per method or task name read better as `increase(...[1h])` than `rate(...)`, with `decimals: 0`.
- Cron "did it run" comes from the task metrics, not from the cron registry table (it keeps rows from old versions).
- Dashboard thresholds must match the alert rule thresholds.

## Editing dashboards through the API

- Saving through the API normalizes the dashboard: submit the native form, rows with `panels: []` and their panels top-level with contiguous `y`. A row that has to start collapsed (or open) is sent holding its children in `panels` with `collapsed` set, and the top-level `y` are recomputed by hand: one unit per collapsed row, with the expanded block kept contiguous. Grafana accepts it and returns the same shape.
- A `remove` operation on a panel leaves its `gridPos` hole behind: Grafana does not refill the layout (the UI behaves the same), so a neighbouring panel has to be moved by hand when the gap matters.
- Check `list_dashboard_versions` before writing and prefer `operations` patches: the user edits dashboards by hand and a full-JSON overwrite silently reverts that work.

## Reading the API by hand

- The MCP authenticates with a service account token (`GRAFANA_SERVICE_ACCOUNT_TOKEN`, set in the environment of the MCP server), and a service account has no user behind it: a 403 is never the token being bad. On `/api/user` and `/api/user/orgs` it is normal; on a single dashboard (`Access denied to this dashboard`, which is not a 404) it means that identity cannot see it, a permission on that dashboard or its folder. Restarting the MCP or rotating the token changes nothing, and `X-Grafana-Org-Id` does not help when the identity belongs to another organization.
- Listing is reliable, filtering is not: a dashboard search with a text query answers empty in this instance (through the tool and through `/api/search?query=...` alike), and `list_prometheus_metric_names` also lists names that survive only in the retained series of a metric that stopped being published. Use the listing without a query, and `count(<metric>)` to tell whether a metric is alive today.
- The MCP Prometheus tool can answer empty for every query, `up` included, while the datasource itself is fine: run PromQL by hand with `grafana_api_request` against `/api/datasources/uid/<uid>/resources/api/v1/query?query=...`.
- `curl` and `python` from the shell cannot reach the Grafana host of the deployment: Cloudflare blocks them (`error code: 1010`, which looks like a 403 and has nothing to do with Grafana). Probe the API through the MCP (`grafana_api_request`), which is what the deployment lets through.
- A Loki query by hand goes through `GET /api/datasources/proxy/uid/<loki-uid>/loki/api/v1/query_range?query=<urlencoded>&start=<ns>&end=<ns>&step=<seconds>` (the range comes back as `resultType: matrix`, one series per group). For Loki the `/api/datasources/uid/<uid>/resources/...` route answers 500, and `/api/ds/query` is not reachable from `grafana_api_request` at all (404), even though that is the path the dashboards use.

## Gotchas

- PromQL literal dots need `\\.`; simpler to use `[.]` or a plain dot.
- The panel-level `datasource` must match its targets'. A SQL table declared as Prometheus renders empty even though the query returns rows.
- Shortening a host in a legend needs a short label in the query: `renameByRegex` renames the field name, but with `legendFormat` set the legend text comes from the datasource.
  - Prometheus: `label_replace(<expr>, "host", "$1", "instance", "([^.]+)[.][^.]+.*")` plus `legendFormat: "... {{host}}"`.
  - Loki metric query: `| label_format host="{{ regexReplaceAll "[.][^.]*[.][^.]*[.][^.]*$" .instance "" }}"` plus `legendFormat: "{{host}}"`. Quotes inside the template must be escaped as `\"`, or LogQL closes the string early (`not a valid duration string`).
- Tables from a Prometheus raw selector carry a `__name__` column; hide it with `organize`.
- Panel images cannot be rendered (no image renderer installed) and there is no browser here: verify a dashboard by running its queries with the variables substituted.
- Signals that exist only as logs are counted with Loki, and the count is then cross-checked against the nginx access log for the same window and the same status code (`job="frontend"`, e.g. a `429`): two independent sources must agree before a number is trusted.
