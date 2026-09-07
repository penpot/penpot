# Graph Experiment

## Scope

- Purpose: project Penpot file data into an embedded Ladybug graph database.
- Purpose: keep the graph current with Penpot file changes.
- Purpose: expose a read-only graph console for backend debugging.
- This is an experiment, not a replacement for PostgreSQL file storage.
- The graph subsystem is off unless `:graph` is in the backend flags.
- The main Penpot frontend has no graph feature code for this subsystem.
- The graph console is a backend-served HTML template with JavaScript.

## Memory Links

- Read `mem:backend/core` for backend architecture, HTTP routes, DB rules, and test commands.
- Read `mem:backend/rpc-db-worker-subtleties` for RPC and message bus behavior.
- Read `mem:backend/http-storage-filedata-subtleties` for file data loading and realization.
- Read `mem:common/changes-architecture` for the change record vocabulary.
- Read `mem:frontend/routing-app-shell-subtleties` for the existing notification WebSocket.
- Read `mem:prod-infra/core` for Redis or Valkey message bus topology.

## Branch Surface

- The graph experiment adds about 6,336 lines and changes about 27 files.
- The graph implementation lives under `backend/src/app/graph/`.
- The graph console lives at `backend/resources/app/templates/graph-console.tmpl`.
- The existing debug page gains graph links in `backend/resources/app/templates/debug.tmpl`.
- The existing debug HTTP routes gain graph handlers in `backend/src/app/http/debug.clj`.
- The backend system passes the message bus to the debug route component in `backend/src/app/main.clj`.
- The backend adds Ladybug and Arrow dependencies in `backend/deps.edn`.
- The backend adds JVM options for Ladybug and Arrow native access.
- The common flag registry adds `:graph` in `common/src/app/common/flags.cljc`.
- The graph experiment adds `graph_sync_parity_test.clj` and `graph_binder_gate_test.clj`.

## System Model

### Storage layers

- PostgreSQL remains the source of truth for Penpot files.
- The graph database stores a projection of one file.
- A persistent graph uses a `.lbug` path under `PENPOT_GRAPH_DIR`.
- The default graph directory is `/tmp/penpot-graph`.
- A debug session uses a Ladybug `:memory:` database.
- A debug session database lives inside the backend JVM process.
- A debug session does not survive a backend restart.
- A debug session does not store file data back to PostgreSQL.

### Two graph update paths

- Cold projection reads the complete file and rebuilds the graph.
- Incremental sync reads file change records and updates the open graph.
- Both paths must produce the same graph for the same file state.
- The parity test treats cold projection as the reference path.
- A reload discards the session graph and uses cold projection again.

## Main Namespaces

### `app.graph.ladybug`

- Opens and closes Ladybug `Database` and `Connection` objects.
- Installs and loads the Ladybug JSON extension.
- Executes Cypher statements.
- Executes prepared statements.
- Binds scalar parameters.
- Formats UUID, string, integer, number, JSON, and timestamp values.
- Formats compound values such as arrays, maps, and structs.
- Converts Ladybug values back to Clojure values.
- Limits normal query results to 200 rows by default.
- Detects result truncation with `:truncated?`.
- Uses query timeout `0` by default.
- Query timeout `0` disables the timeout.
- Provides `validate-on-connection!` for parse, bind, and read-only checks.
- `exec-prepared-on-connection!` prepares every statement before the first execution.
- A prepare failure stops the batch before a mutation runs.

### `app.graph.schema`

- Provides the public schema facade.
- Exposes schema version `penpot-graph-slice-4`.
- Delegates node and relationship definitions to `app.graph.schema.nodes`.

### `app.graph.schema.nodes`

- Holds the single registry for graph node tables.
- Generates node DDL.
- Generates relationship DDL.
- Maps Penpot shape types to graph tables.
- Projects source attributes into graph attributes.
- Formats graph column values.
- Quotes reserved graph labels such as `Group` and `Boolean`.
- Defines container tables and shape tables.
- Defines `IsChildOf`, `IsInstanceOf`, `RefersTo`, and `FillsSwapSlot`.

### `app.graph.schema.contract`

- Records deliberate graph contract decisions.
- Renames graph columns such as `:revn` to `revision`.
- Drops attributes that do not belong in this graph slice.
- Records attributes that the graph does not project.
- Applies per-table dropped attributes.
- Defines type overrides for vectors, transforms, colors, maps, and JSON arrays.
- Maps selected map keys to the frontend JSON naming convention.
- `:background-blur` remains a declared unprojected attribute.

### `app.graph.schema.projection`

- Derives projected schemas from canonical Malli schemas.
- Builds the projected document schema.
- Builds projected shape schemas.
- Selects the schema for each shape type.

### `app.graph.schema.types`

- Maps Malli types to Ladybug types.
- Maps matrices to `DOUBLE[6]`.
- Maps points to `DOUBLE[2]`.
- Maps rectangles to `DOUBLE[4]`.
- Maps colors to `UINT32`.
- Maps collections to Ladybug arrays.
- Maps `:map-of` schemas to `MAP`.
- Maps closed scalar maps to `STRUCT`.
- Maps other complex values to `JSON`.

### `app.graph.schema.values`

- Coerces source values to graph column values.
- Writes fixed vectors with deterministic order.
- Packs colors into the graph color representation.
- Sorts set values when deterministic output is needed.

### `app.graph.arrow`

- Loads projection rows with Apache Arrow.
- Creates temporary staged node and relationship tables.
- Uses `COPY ... FROM (MATCH ...)` for bulk loading.
- Groups relationship loads by source and target table pair.
- Resolves relationship endpoints with joins.
- Does not use `createArrowRelTable` for UUID relationship endpoints.
- Keeps the Arrow `RootAllocator` alive until Ladybug releases staged buffers.
- Closes the allocator after the connection and database close sequence.

### `app.graph.ingest`

- Fetches a complete file with `bfc/get-file` and `:realize? true`.
- Rejects missing files.
- Rejects files without file data.
- Can run file data validation before projection.
- Creates the DDL.
- Loads nodes and edges through Arrow.
- Executes post-load transforms.
- Writes graph metadata last.
- Treats the final metadata write as the complete-build marker.
- Supports a persistent database path and an open connection.

### `app.graph.projection.document`

- Projects `Document`, `Page`, `Component`, and supported shape nodes.
- Skips the page root frame.
- Creates `IsChildOf` edges from shapes to parents.
- Creates page edges to the document.
- Creates component edges to the document.
- Stores page order in `Page.index` and edge `position`.
- Reverses the stored `:shapes` list for Penpot z-order.
- Adds `page-id` to every projected shape.
- Propagates an instance head `component-id` to descendants.
- Stops component inheritance at a non-Frame shape with its own component ID.
- Skips deleted components during cold projection.
- Logs unsupported shape types and missing shape records.

### `app.graph.projection.transforms`

- Runs after the base nodes and edges load.
- `link-component-instances` creates `IsInstanceOf` edges.
- A Frame needs `component-file` to qualify as an instance head.
- `link-shape-refs` creates `RefersTo` edges from `shape-ref`.
- Ladybug limits multi-label relationship `MERGE` statements.
- The transform emits one statement for each shape-table pair.
- `link-swap-slots` creates `FillsSwapSlot` edges.
- Swap slot IDs come from `swap-slot-<uuid>` entries in `touched`.
- The transform removes swap slot entries from `touched` after edge creation.
- The transform order matters because it reads and then changes `touched`.

### `app.graph.meta`

- Stores graph provenance in `GraphMeta`.
- Stores schema version, source revision, producer, and build time.
- The source revision identifies the file revision used for cold projection.

### `app.graph.stats` and `app.graph.report`

- `app.graph.stats` counts graph nodes and relationships from the live catalog.
- `app.graph.report` prints ingest information for REPL use.

## Cold Projection Flow

1. Get the file row and realized file data from PostgreSQL.
2. Read the file revision from the file row.
3. Build the node and edge projection.
4. Create all graph tables from the graph schema.
5. Load node rows with Arrow.
6. Load relationship rows with Arrow.
7. Run `CHECKPOINT;`.
8. Run the registered derived transforms.
9. Write `GraphMeta` as the final build step.
10. Return file ID, file revision, database path, projection stats, and transform stats.

### Projection node groups

- `Document` contains file-level attributes without the file data blob.
- `Document.options` receives file-level options from the data blob.
- `Page` contains page attributes without the page object map.
- `Component` contains component attributes without component object maps.
- Shape tables contain the supported shape attributes.
- The graph stores selected derived attributes such as `page-id`.

### Projection relationship groups

- Structural edges use `IsChildOf`.
- Page and component edges point to `Document`.
- Derived edges come from the post-load transform registry.

## Incremental Sync

### Change source

- `app.rpc.commands.files-update` persists the file update first.
- The same command publishes a `:file-change` message to the file topic.
- The topic key is the file UUID.
- The message contains the file ID, profile ID, session ID, revision, version, and changes.
- Library changes also publish a team-topic message.
- The graph session only consumes the file-topic `:file-change` messages.

### Session subscription

- `app.graph.debug/start-sync-loop!` creates a channel with a dropping buffer of 64.
- The session subscribes the channel to the file UUID topic.
- The loop reads one message at a time.
- The loop ignores message types other than `:file-change`.
- The loop stops when the channel closes.
- `destroy-session!` closes the channel and purges its message bus subscription.

### Session state

- Sessions are stored in a global `defonce` atom.
- The map key is the string form of `profile-id`.
- One profile has one graph session.
- Loading another file first destroys the old session.
- A session stores the Ladybug database and connection.
- A session stores a shared lock for graph access.
- A session stores file metadata.
- A session stores the incremental sync index.
- A session stores the message bus channel.
- A session stores load time and profile ID.
- The session keeps projection statistics but drops full projection rows after index creation.

### Sync index

- `build-index` starts from the complete cold projection.
- The index stores the graph file ID and document ID.
- The index stores the current graph revision.
- The index stores page IDs, names, and positions.
- The index stores component IDs, names, and deleted state.
- The index stores shape table, parent, position, frame, page, and component context.
- The index stores child IDs by parent ID.
- The index supports later change application without another PostgreSQL file read.

### Change application

- `apply-changes!` processes the change list in source order.
- Each supported change returns a new index and a list of Cypher statements.
- Unsupported changes enter the `:skipped` result.
- Supported changes enter the `:applied` result.
- The function collects all statements before it executes them.
- The function appends a document revision statement when at least one change applies.
- The index revision advances only when at least one change applies.
- A larger incoming revision than the index revision creates a warning.
- A revision gap does not trigger catch-up.

### Shape change rules

- `:add-obj` reuses `projection.document/denormalized-shape`.
- `:add-obj` creates the shape node and its parent edge.
- `:mod-obj` applies supported `:set` operations to graph columns.
- `:mod-obj` keeps false and zero values as values.
- `:del-obj` deletes shapes in deep post-order.
- `:mov-objects` detaches shapes from the old parent.
- `:mov-objects` closes the old sibling position gap.
- `:mov-objects` inserts shapes at the new position.
- `:mov-objects` updates `parent_id` and `frame_id`.
- `:mov-objects` rewrites container `shapes` values.
- The parent columns and child lists must match a cold projection.

### Page and component change rules

- Page add creates a projected page node and a document edge.
- Page delete removes the page subtree.
- Page modification updates supported page attributes.
- Component add creates a component node and document edge.
- Component modification updates supported component attributes.
- Component delete uses a soft-delete state.
- Component restore removes the soft-delete state.
- Component purge removes the component node and document edge.
- Component sync paths need more parity coverage than the current tests provide.

## Session Locking

- The sync loop and HTTP handlers share one lock per session.
- The lock protects one Ladybug connection from concurrent access.
- Queries acquire the lock before binder validation and execution.
- Graph data export acquires the lock before catalog reads.
- Session export acquires the lock before `EXPORT DATABASE`.
- A long query blocks sync for the same session.
- A sync batch blocks queries for the same session.
- Ladybug connection thread safety is not assumed.

## Graph Query Rules

- The console accepts Cypher text.
- Blank query text raises a validation error.
- The query first passes Ladybug prepare and bind checks.
- The query must pass the engine read-only analysis.
- A mutating query is rejected.
- The graph console does not provide a write path.
- A session graph is rebuilt from the file by Reload.
- Normal query results have a 200-row limit.
- Query results use string values for the HTML console representation.
- JSON requests receive a Transit JSON response with the query and result.
- HTML requests receive the rendered console with the result.

## Graph Data Export

### G6 data

- `/dbg/actions/graph-data` reads the live Ladybug database.
- It does not read the sync index for nodes and edges.
- It therefore shows database drift if a batch fails after index update.
- Node export covers all registered node tables.
- Relationship export reads the Ladybug relationship catalog.
- Relationship export includes source, target, relationship name, and position.
- Node and relationship export uses a 100,000-row limit.
- The response reports `truncated` when a limit cuts the result.
- The response reports buffer-manager memory usage.

### `.lbug` export

- `source=file` rebuilds the persistent graph from PostgreSQL file data.
- `source=file` runs a synchronous full ingest for each request.
- `source=session` exports the caller profile's live in-memory graph.
- Session export uses Ladybug `EXPORT DATABASE` to Parquet files.
- Session export creates a new `.lbug` database with `IMPORT DATABASE`.
- The temporary Parquet staging directory is deleted after import.
- The final session `.lbug` file remains in the system temporary directory.
- The HTTP response streams the database file to the caller.

## HTTP Routes and Access

- The graph routes live in `backend/src/app/http/debug.clj`.
- The graph route list is added only when `:graph` is enabled.
- `/dbg/graph` serves the graph console page.
- `/dbg/actions/graph-files` returns the profile file tree.
- `/dbg/actions/graph-load` loads a file into the profile session.
- `/dbg/actions/graph-unload` closes the profile session.
- `/dbg/actions/graph-reload` rebuilds the loaded file graph.
- `/dbg/actions/graph-query` runs a read-only Cypher query.
- `/dbg/actions/graph-sync-status` returns the sync state.
- `/dbg/actions/graph-data` returns nodes and edges for G6.
- `/dbg/actions/graph-export` streams a `.lbug` database.
- The `/dbg` session middleware remains active.
- The `/dbg` admin middleware remains active.
- A devenv host with a profile ID passes the debug authorization rule.
- Other hosts need a profile email in the configured admin set.
- `/dbg/actions/graph-files` lists reachable teams, projects, and files.
- The file tree query has a 500-file limit.
- The graph handlers resolve graph namespaces at call time.
- The backend requires `app.graph.debug` and `app.graph.ingest` when the flag is on.
- Ladybug native loading then fails during route initialization instead of first use.

## Console Frontend

### Page type

- `graph-console.tmpl` is a backend resource template.
- It is not a Rumext component.
- It is not part of the main frontend route table.
- The page uses browser `fetch` calls and a browser WebSocket.
- The page loads G6 version `5.1.1` from jsDelivr.

### File tree

- The page fetches `/dbg/actions/graph-files`.
- The response contains team, project, and file groups.
- The page creates the tree with DOM APIs.
- A file click submits the graph load form.
- The page shows a message when no file exists.

### Graph rendering

- The page fetches `/dbg/actions/graph-data`.
- The page converts graph nodes and edges to G6 data.
- The page skips repaint when the node and edge signature does not change.
- The page marks added, removed, and changed graph entities.
- The page supports tree, dagre, circular, force, and combo layouts.
- The page supports collapsed container combos.
- The page has render guards at 4,000 nodes and 8,000 edges.
- The `?safe` query option bypasses the render guard.
- The page shows graph size by node count and relationship count.
- The page shows buffer-manager memory in MiB.
- The page reports a CDN failure when G6 is undefined.

### Query result filtering

- A query can return `filter_*` columns with node IDs.
- The HTML result table hides columns with the `filter_` prefix.
- The JSON result keeps the full result.
- The graph view uses the hidden IDs to select matching nodes.
- The graph view re-runs the query after graph refresh.
- This keeps the query filter aligned with the current graph.
- A user column named `filter_*` follows the same hiding rule.

### Node inspector

- A node click creates a query for that node.
- The inspector calls `/dbg/actions/graph-query` with JSON negotiation.
- The inspector displays the full projected row.
- The inspector uses table and ID values from the graph data.

## WebSocket Data Flow

1. The page opens `/ws/notifications` with a random `session-id` query value.
2. The page sends `:subscribe-file` with a Transit UUID value.
3. The server makes sure that the file exists and that the profile has read permission.
4. The server subscribes the connection to the file topic.
5. `files_update` publishes `:file-change` to the same topic.
6. The graph session consumes the message from its message bus subscription.
7. The WebSocket server sends the message to the browser connection.
8. The browser adds the change to the changelog.
9. The browser fetches sync status after 150 milliseconds.
10. The browser fetches graph data after a 400-millisecond debounce.
11. The browser repaints the G6 graph when the graph data changes.

### WebSocket reconnect behavior

- The page reconnects after three seconds when the socket closes.
- The page resubscribes to the file after the socket opens.
- The page refreshes sync status after reconnect.
- The page refreshes graph data after reconnect.
- Reconnect does not recover dropped message-bus changes.
- The page shows the sync error or skipped-change state when the status reports it.

## Feature Flag and Runtime Dependencies

- `:graph` is defined in `common/src/app/common/flags.cljc`.
- The flag is off by default.
- `com.ladybugdb/lbug` version `0.19.1` is a backend dependency.
- `org.apache.arrow/arrow-memory-netty` version `18.2.0` supports Arrow `RootAllocator`.
- The JVM uses `--enable-native-access=ALL-UNNAMED`.
- The JVM uses `--add-opens=java.base/java.nio=ALL-UNNAMED`.
- The JVM uses `--sun-misc-unsafe-memory-access=allow`.
- The JVM options appear in the development alias and backend launch scripts.
- A Ladybug version change needs new binder and parity tests.
- A JDK version change needs a startup test with the graph flag enabled.

## Tests

### `backend-tests.graph-sync-parity-test`

- Uses two Ladybug `:memory:` databases.
- Does not use PostgreSQL or a live graph session.
- Projects initial file data into database A.
- Applies changes to database A through incremental sync.
- Applies the same changes to file data.
- Projects the changed file data into database B.
- Compares every node row and relationship row.
- Reports differences by table, row key, and column.
- Covers shape add, shape modification, shape deletion, movement, and page changes.
- Contains a test that injects a sync defect and expects a graph difference.
- Does not cover all component change variants.
- Does not cover every movement insertion mode.

### `backend-tests.graph-binder-gate-test`

- Creates the live graph DDL in a Ladybug `:memory:` database.
- Prepares each sync statement template without executing it.
- Detects parse errors and missing tables.
- Detects missing columns and bad label quoting.
- Reports the expected read-only classification.
- Covers reserved node labels across the node registry.
- Reports an error result for an invalid statement.

### Test gaps

- No automated HTTP handler tests cover graph routes.
- No automated session lifecycle tests cover load and unload.
- No automated WebSocket tests cover graph subscription.
- No automated export tests cover persistent and session sources.
- Component add, modify, delete, restore, and purge need parity tests.
- Page delete needs parity coverage.
- Movement with `:after-shape` needs parity coverage.
- Buffer overflow and revision gap behavior need tests.
- Partial batch failure and recovery need tests.
- Query timeout and long-query behavior need tests.

## Known Risks and Limits

### Dropped changes

- The sync channel uses a dropping buffer of 64.
- A burst can discard file-change messages.
- The sync loop logs a revision gap when it sees a larger revision.
- The sync loop does not fetch missing rows from `file_change`.
- Reload is the only built-in recovery path.

### Partial batch state

- `apply-changes!` does not provide Ladybug transaction atomicity.
- A statement failure can leave a partly changed graph.
- The in-memory index can advance before the database state is complete.
- `/dbg/actions/graph-data` reads the database and exposes this drift.
- Reload rebuilds the graph from PostgreSQL file data.

### Query resource use

- The default session query timeout is zero.
- A costly query can hold the session lock for a long time.
- The same lock blocks incremental sync.
- The graph export also holds the same lock during catalog reads.
- The graph schema has a high memory floor.
- The console reports about 115 MiB for the wide slice before file data.

### Session lifecycle

- Sessions have no TTL.
- Sessions remain until unload, replacement, or process shutdown.
- Each session owns native Ladybug memory.
- Many profiles can create many native databases.
- A profile load replaces its previous session.
- Two browser tabs for one profile share one graph session.

### Temporary files

- Session export leaves the final `.lbug` file in the system temporary directory.
- Long-lived servers can accumulate exported session databases.
- The staging directory is deleted after import.

### Browser dependency

- The graph view depends on a runtime CDN request.
- A network restriction can remove the G6 view.
- Queries and session status still use backend endpoints without G6.

### Data exposure

- The graph console can list many files available to the profile.
- The console can load complete projected file data.
- The console can export a graph database.
- The console can inspect all projected node attributes.
- The console is safe only when the `/dbg` access boundary is correct.
- The graph flag must remain off for deployments that do not need this tool.

### Contract drift

- The graph schema is a deliberate slice of the Penpot file model.
- New source attributes do not enter the graph automatically in all cases.
- Dropped and unprojected attributes need an explicit contract decision.
- `applied_tokens` key mapping depends on the JSON naming function.
- `filter_*` is a frontend convention, not a graph schema guarantee.

### Ladybug dialect coupling

- Cypher strings contain Ladybug-specific syntax.
- Label quoting handles reserved labels explicitly.
- Relationship transforms depend on Ladybug relationship limits.
- Arrow loading depends on Ladybug `COPY FROM (MATCH ...)` behavior.
- A dependency upgrade needs schema, binder, Arrow, and parity checks.

## REPL Helpers

- `app.srepl.main` resolves graph functions only when a helper runs.
- `graph-smoke-test!` runs a basic Ladybug operation.
- `graph-query-test!` runs a graph query test.
- `ingest-file-to-graph!` projects a file into a graph database.
- These helpers use `requiring-resolve` to keep the graph dependency lazy.

## Operational Invariants

- PostgreSQL file data remains authoritative.
- Cold projection and incremental sync must produce equal graph state.
- The graph revision must identify the last applied file revision.
- The document revision must update when a sync batch applies.
- A missing or skipped change must remain visible in sync status.
- A graph query from the console must be read-only.
- A graph session must serialize connection access.
- Graph routes must remain behind the `:graph` flag and `/dbg` access control.
- The Arrow allocator must outlive all Ladybug operations that use its buffers.
- `GraphMeta` must be written after the full ingest and transforms finish.

## Key Files

- `backend/src/app/graph/ladybug.clj`: Ladybug API and query gates.
- `backend/src/app/graph/arrow.clj`: Arrow bulk load.
- `backend/src/app/graph/ingest.clj`: Complete file ingest.
- `backend/src/app/graph/debug.clj`: Session lifecycle, sync loop, query, and export.
- `backend/src/app/graph/sync.clj`: Incremental change application.
- `backend/src/app/graph/meta.clj`: Graph provenance.
- `backend/src/app/graph/stats.clj`: Graph counts.
- `backend/src/app/graph/report.clj`: REPL ingest report.
- `backend/src/app/graph/projection/document.clj`: Base document projection.
- `backend/src/app/graph/projection/transforms.clj`: Derived relationship transforms.
- `backend/src/app/graph/schema/nodes.clj`: Node and relationship registry.
- `backend/src/app/graph/schema/contract.clj`: Projection contract decisions.
- `backend/src/app/graph/schema/projection.clj`: Malli projection schemas.
- `backend/src/app/graph/schema/types.clj`: Malli-to-Ladybug type mapping.
- `backend/src/app/graph/schema/values.clj`: Value coercion.
- `backend/src/app/http/debug.clj`: Graph route registration and handlers.
- `backend/src/app/http/websocket.clj`: File WebSocket subscription handlers.
- `backend/src/app/rpc/commands/files_update.clj`: File-change publication.
- `backend/src/app/main.clj`: Integrant message bus wiring.
- `backend/resources/app/templates/graph-console.tmpl`: Graph console browser code.
- `backend/resources/app/templates/debug.tmpl`: Debug page graph links.
- `common/src/app/common/flags.cljc`: `:graph` feature flag.
- `backend/test/backend_tests/graph_sync_parity_test.clj`: Cold versus sync parity.
- `backend/test/backend_tests/graph_binder_gate_test.clj`: Cypher binder gate.

## Development Commands

- Run backend commands from the `backend/` directory.
- Run focused parity tests with `clojure -M:dev:test --focus backend-tests.graph-sync-parity-test`.
- Run focused binder tests with `clojure -M:dev:test --focus backend-tests.graph-binder-gate-test`.
- Run the backend test suite with `clojure -M:dev:test`.
- Examine Clojure formatting with `pnpm run check-fmt:clj`.
- Run backend Clojure lint with `pnpm run lint:clj`.
- Write test output to a file before reading or filtering it.
