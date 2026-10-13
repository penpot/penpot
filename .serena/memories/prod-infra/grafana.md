# Grafana

Private instance with Penpot metrics and logs. Dashboards are edited through the Grafana API (MCP tools); there is no dashboards-as-code for them.

Keep this memory free of internal names: no host names, no deployed versions, no topology. The datasource ids and the label conventions are the useful technical part.

## Datasources

- Metrics — **Prometheus**, uid `xkRmyFBGk`. PromQL. Metric families: `penpot_*` (RPC, task timings, CLIMIT, HTTP, websocket, storage), `hikaricp_*`, `jvm_*` and `process_*` on the backend (`job="app"`); `pg_*` from the Postgres exporter (`job="postgres"`); `redis_*` from the Redis exporter (`job="redis"`); `node_*` and `container_*` from node-exporter and cAdvisor. Backend histograms are in **milliseconds**.
- Logs — **Loki**, uid `h3CGKhEMk`. LogQL, and filter by the structured metadata `logger`/`level` (`{job="penpot"} | logger="..." | level="ERROR"`). Signals that only exist as logs: the profile behind a CLIMIT queue (`logger="app.rpc.climit"`, whose `id` carries the key). CLIMIT rejections have their own counters (`penpot_rpc_climit_rejected_total`, by `name` and `reason`), and their `hint="rejected"` line goes out at WARN only when something stays queued, so counting the lines is not counting the rejections.
- SQL — **Postgres**, uid `y8ea9tfMk`. Panels need `rawQuery: true`, `editorMode: "code"` and `format: "table"`. The `task` table is the only truth about the queue depth. It is a **read-only replica**: it rejects any write, `CREATE TEMP TABLE` included, so queries through it must be plain `SELECT`s. It also cuts long-running queries (the MCP path has a short timeout), so bound them (e.g. a list of ids); an empty answer there is not proof that there is no data.
- **Postgres (telemetry)**, uid `9YAUuM_Gz`.
- `$__rate_interval` and `$__interval` are substituted by Grafana; use them instead of fixed windows in graphs.

## Main dashboard

- `Production status`, uid `penpot-production-status`. Sections: general state and deploy, RPC y CLIMIT, PostgreSQL and pool, Redis/msgbus, tasks, JVM/process. Variables `$env` and `$host` (custom), no auto refresh. Every section opens collapsed except RPC y CLIMIT, which is the one read on a normal day.
- Per-host investigation dashboards are separate, and the alert rules deeplink into them.

## Conventions

- Filter with `instance=~"$host.$env.<domain>.*"`. To know which instances serve, count the ones reporting JVM metrics; `up{job="app"}` is not reliable (it can report 1 while the endpoint answers an empty body).
- Layout: a band of number-only panels at the top of each section, wider data panels below, tables tall enough to show every row, legend on the **right** sorted by last value descending (`sortBy: "Last *"`, `sortDesc: true`).
- Panels comparing used against total need `defaults.min = 0`, else the automatic axis exaggerates the difference.
- Counters per method or task name read better as `increase(...[1h])` than `rate(...)`, with `decimals: 0`.
- Cron "did it run" comes from the task metrics, not from the cron registry table (it keeps rows from old versions).
- Dashboard thresholds must match the alert rule thresholds.

## Gotchas

- PromQL literal dots need `\\.`; simpler to use `[.]` or a plain dot.
- The panel-level `datasource` must match its targets'. A SQL table declared as Prometheus renders empty even though the query returns rows.
- Shortening a host in a legend needs a short label in the query: `renameByRegex` renames the field name, but with `legendFormat` set the legend text comes from the datasource.
  - Prometheus: `label_replace(<expr>, "host", "$1", "instance", "([^.]+)[.][^.]+.*")` plus `legendFormat: "... {{host}}"`.
  - Loki metric query: `| label_format host="{{ regexReplaceAll "[.][^.]*[.][^.]*[.][^.]*$" .instance "" }}"` plus `legendFormat: "{{host}}"`. Quotes inside the template must be escaped as `\"`, or LogQL closes the string early (`not a valid duration string`).
- Tables from a Prometheus raw selector carry a `__name__` column; hide it with `organize`.
- Saving through the API normalizes the dashboard (rows may be flattened, `y` recomputed): submit the native form (rows with `panels: []`, panels top-level, contiguous `y`).
- To collapse a row, submit the whole dashboard with that row holding its children in `panels` and `collapsed: true` (the open rows keep `panels: []` and their panels top-level): Grafana accepts it and returns the same shape. The top-level `y` have to be recomputed by hand, stepping one unit per collapsed row and keeping the expanded block contiguous.
- A `remove` operation on a panel leaves its `gridPos` hole behind: Grafana does not refill the layout, so a neighbouring panel has to be moved by hand when the gap matters (deleting a panel in the UI behaves the same).
- Panel images cannot be rendered (no image renderer installed) and there is no browser here: verify a dashboard by running its queries with the variables substituted.
- Signals that exist only as logs are counted with Loki, and the count is then cross-checked against the nginx access log for the same window and the same status code (`job="frontend"`, e.g. a `429`): two independent sources must agree before a number is trusted.
- The app writes its own logs at WARN only when something is left queued behind the call and at TRACE otherwise, so production Loki holds just the under-pressure lines: a count of `hint="enqueued"` or `hint="acquired"` is not the number of calls, and neither is a count of `hint="rejected"` lines, because a rejection that leaves the queue empty (a lone caller that times out) is written at TRACE. The rejection counters are the real number.
- The MCP Prometheus tool can answer empty for every query, `up` included, while the datasource itself is fine: an empty result from it is not proof that a panel has no data, and blaming the datasource for it is wrong. Run PromQL by hand with `grafana_api_request` against `/api/datasources/uid/<uid>/resources/api/v1/query?query=...`, and check when a series is expected but the tool says nothing.
- The MCP connects with a service account token (`GRAFANA_SERVICE_ACCOUNT_TOKEN` against `https://metrics.penpot.dev/`), and a service account has no user behind it: `user_info` (`GET /api/user`) and `/api/user/orgs` answer 403 while `/api/org`, the datasources and the dashboards work. A 403 there is normal and does not mean the token is broken.
- A 403 on one dashboard with `Access denied to this dashboard` (not a 404) means it exists but the token's identity cannot see it, while `/api/search` without a query still lists the ones it can. That is a permission on that dashboard or its folder, not a credential problem, and restarting the MCP changes nothing. `X-Grafana-Org-Id` does not help when the identity belongs to another organization.
- Dashboard search with a text query answers empty in this instance, through the tool and through `/api/search?query=...` alike, even for terms that are in the titles of visible dashboards. Listing without a query is reliable; never conclude from a text search that a dashboard does not exist.
- `curl` and `python` from the shell cannot reach `metrics.penpot.dev`: Cloudflare blocks them (`error code: 1010`), and the answer looks like a 403 that has nothing to do with Grafana permissions. Probe the API through the MCP (`grafana_api_request`), which is what the deployment actually lets through.
- A Loki query by hand goes through `GET /api/datasources/proxy/uid/<loki-uid>/loki/api/v1/query_range?query=<urlencoded>&start=<ns>&end=<ns>&step=<seconds>` (the range comes back as `resultType: matrix`, one series per group). For Loki the `/api/datasources/uid/<uid>/resources/...` route answers 500, and `/api/ds/query` is not reachable from `grafana_api_request` at all (404), even though that is the path the dashboards use.
- `list_prometheus_metric_names` also lists names that survive only in the retained series of a metric that stopped being published, so it does not say whether a metric is alive today (after the CLIMIT deploy removed `penpot_rpc_climit_queue`, that name was still there). To tell whether a deployment landed, query the value (`count(<metric>)`) instead of the name list.
- **Check `list_dashboard_versions` before writing.** The user edits dashboards by hand and a full-JSON overwrite silently reverts that work; prefer `operations` patches per panel.
