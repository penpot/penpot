;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.debug
  "In-memory Ladybug sessions for the debug graph console."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.graph.ingest :as graph.ingest]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]
   [app.graph.sync :as graph.sync]
   [app.msgbus :as mbus]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [promesa.exec.csp :as sp])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.Database))

(set! *warn-on-reflection* true)

(def default-query
  "Default console query, written to be self-explanatory in the textarea.
  The `filter_*` columns carry node ids for the graph-view result filter;
  the results table hides them (see `hide-filter-columns` and the
  template's `renderQueryOutput`)."
  (str "MATCH (s)-[r]->(t)\n"
       "// WHERE some condition\n"
       "RETURN label(s) AS src, s.name,\n"
       "       label(r) AS rel,\n"
       "       t.name, label(t) AS tgt,\n"
       "\n"
       "// filter_* columns omitted from table; these needed for graph view\n"
       "s.id AS filter_src_id, t.id AS filter_tgt_id;"))

(defonce ^:private sessions
  (atom {}))

(defn- session-key
  [profile-id]
  (str profile-id))

(defn- destroy-session!
  [{:keys [conn db sync-ch msgbus]}]
  (when sync-ch
    (sp/close! sync-ch)
    (when msgbus
      (mbus/purge! msgbus [sync-ch])))
  (when conn
    (ex/ignoring (.close ^Connection conn)))
  (when db
    (ex/ignoring (.close ^Database db))))

(defn- slim-ingest-meta
  "Drop full projection rows from session meta.

  `build-index` needs `:nodes`/`:edges` once; keeping them in the session
  duplicates the entire graph on the JVM heap for every Load."
  [meta]
  (update meta :projection #(select-keys % [:stats])))

(defn- format-cell
  [value]
  (cond
    (nil? value) "NULL"
    (string? value) value
    :else (str value)))

(defn- format-query-result
  [{:keys [columns rows truncated?]}]
  {:columns (mapv str columns)
   :rows    (mapv (fn [row]
                    (mapv format-cell row))
                  rows)
   :truncated? truncated?
   :row-count (count rows)})

(defn- apply-file-change!
  [conn profile-id {:keys [changes revn file-id]}]
  (try
    (some-> (get @sessions (session-key profile-id))
            (as-> current
                  (when (= file-id (:file-id current))
                    (let [lock   (:lock current)
                          result (locking lock
                                   (graph.sync/apply-changes!
                                    conn (:index current) changes revn))
                          sync-at (ct/now)]
                      (swap! sessions assoc-in [(session-key profile-id) :index]
                             (:index result))
                      (swap! sessions update-in [(session-key profile-id) :meta]
                             (fn [meta]
                               (cond-> (-> meta
                                           (update :sync dissoc :error)
                                           (assoc-in [:sync :last-at] sync-at)
                                           (assoc-in [:sync :last-applied] (:applied result))
                                           (assoc-in [:sync :last-skipped] (:skipped result)))
                                 (seq (:applied result))
                                 (assoc :revn (:revn result)))))
                      (when (seq (:skipped result))
                        (l/dbg :hint "graph sync skipped changes"
                               :file-id (str file-id)
                               :revn revn
                               :skipped (:skipped result)))))))
    (catch Throwable cause
      (l/wrn :hint "graph sync failed"
             :file-id (str file-id)
             :cause cause)
      (swap! sessions assoc-in [(session-key profile-id) :meta :sync :error]
             (ex-message cause)))))

(defn- start-sync-loop!
  [{:keys [conn profile-id file-id] :as session}]
  (if-let [msgbus (:msgbus session)]
    (let [sync-ch (sp/chan :buf (sp/dropping-buffer 64))]
      (mbus/sub! msgbus :topic file-id :chan sync-ch)
      ;; Recur ONLY while the channel is open. A bare `(recur)` after
      ;; `take!` returns nil would spin forever and pin this Connection
      ;; (and its Ladybug Database native memory) across every Load.
      (sp/go-loop []
        (when-let [message (sp/take! sync-ch)]
          (when (= :file-change (:type message))
            (apply-file-change! conn profile-id message))
          (recur)))
      (assoc session :sync-ch sync-ch))
    session))

(defn session-info
  "Return a public view of the current session for `profile-id`, if any."
  [profile-id]
  (when-let [{:keys [file-id meta loaded-at index]} (get @sessions (session-key profile-id))]
    {:file-id        file-id
     :name           (:name meta)
     :revn           (:revn meta)
     :graph-revn     (:revn index)
     :schema-version (:schema-version meta)
     :projection     (:projection meta)
     :sync           (:sync meta)
     :loaded-at      (ct/format-inst loaded-at :iso)}))

(defn sync-status
  "Return incremental sync status for the active session."
  [profile-id]
  (when-let [session (get @sessions (session-key profile-id))]
    (let [{:keys [file-id meta index loaded-at]} session]
      {:file-id    file-id
       :revn       (:revn meta)
       :graph-revn (:revn index)
       :sync       (:sync meta)
       :loaded-at  (ct/format-inst loaded-at :iso)})))

(defn unload-session!
  "Close and discard the in-memory graph for `profile-id`."
  [profile-id]
  (when-let [session (get @sessions (session-key profile-id))]
    (destroy-session! session))
  (swap! sessions dissoc (session-key profile-id)))

(defn load-session!
  "Ingest `file-id` into a new in-memory Ladybug database for `profile-id`."
  [cfg profile-id file-id]
  (unload-session! profile-id)
  (let [^Database db (Database.)
        ^Connection conn (Connection. db)
        msgbus     (::mbus/msgbus cfg)]
    (.setQueryTimeout conn 0)
    (ladybug/ensure-extensions! conn)
    (try
      (let [meta  (graph.ingest/ingest-on-connection! cfg conn file-id
                                                      :db-path ":memory:"
                                                      :skip-stats? true
                                                      :skip-validation? true)
            index (graph.sync/build-index file-id (:revn meta) (:projection meta))
            ;; Discard projection rows after indexing — they are only needed
            ;; to seed the sync index and would otherwise leak heap on each Load.
            meta  (slim-ingest-meta meta)
            session
            ;; :lock serializes access to the shared Connection between the
            ;; msgbus sync loop (writes) and HTTP handlers (reads); the Java
            ;; binding gives no thread-safety guarantee for one Connection.
            (-> {:db db
                 :conn conn
                 :lock (Object.)
                 :file-id file-id
                 :meta meta
                 :index index
                 :msgbus msgbus
                 :profile-id profile-id
                 :loaded-at (ct/now)}
                start-sync-loop!)]
        (swap! sessions assoc (session-key profile-id) session)
        meta)
      (catch Throwable cause
        (destroy-session! {:conn conn :db db :msgbus msgbus})
        (throw cause)))))

(defn query-session!
  "Run a read-only `statement` against the in-memory graph for `profile-id`.

  The statement is bound against the live schema before it runs, so a query
  naming a table or a property that does not exist reports the binder's own
  message and executes nothing. The engine's read/write analysis then decides
  whether it may run at all: the console is an inspection surface, and a
  session graph is rebuilt from the file by Reload, so a mutation from here
  would produce a graph no rebuild reproduces."
  [profile-id statement]
  (when (str/blank? statement)
    (ex/raise :type :validation
              :code :missing-query
              :hint "cypher query is required"))
  (if-let [{:keys [conn lock]} (get @sessions (session-key profile-id))]
    (locking lock
      (let [{:keys [ok? error read-only?]} (ladybug/validate-on-connection! conn statement)]
        (when-not ok?
          (ex/raise :type :validation
                    :code :graph-query-invalid
                    :hint error))
        (when-not read-only?
          (ex/raise :type :validation
                    :code :graph-query-not-read-only
                    :hint "the graph console runs read-only queries"))
        (-> (ladybug/query-on-connection! conn statement)
            format-query-result)))
    (ex/raise :type :not-found
              :code :graph-session-not-loaded
              :hint "load a file graph before running queries")))

(def ^:private export-max-rows
  "Row cap for graph-view export queries; far above expected per-file node
  and edge counts. `:truncated` in the export signals when it was hit."
  100000)

(defn- export-nodes
  [conn]
  (reduce
   (fn [acc {:keys [table]}]
     (let [stmt (str "MATCH (n:" (nodes/match-label table)
                     ") RETURN n.id AS id, n.name AS name;")
           {:keys [rows truncated?]}
           (ladybug/query-on-connection! conn stmt :max-rows export-max-rows)]
       (-> acc
           (update :nodes into
                   (map (fn [[id label]]
                          {:id (str id) :label (str label) :table table}))
                   rows)
           (update :truncated? #(or % truncated?)))))
   {:nodes [] :truncated? false}
   nodes/node-types))

(defn rel-tables
  "Every relationship table in the open database, with whether it carries a
  `position` property.

  Read from the catalog rather than listed here, so a newly ported transform's
  rel table appears in the graph view without the console being told about it."
  [conn]
  (for [[table] (:rows (ladybug/query-on-connection!
                        conn "CALL show_tables() WHERE type = 'REL' RETURN name;"
                        :max-rows 1000))
        :let [props (->> (ladybug/query-on-connection!
                          conn (str "CALL table_info('" table "') RETURN *;")
                          :max-rows 1000)
                         :rows
                         (into #{} (map (comp str second))))]]
    {:table table :position? (contains? props "position")}))

(defn- export-edges
  [conn]
  (reduce
   (fn [acc {:keys [table position?]}]
     (let [stmt (str "MATCH (a)-[r:`" table "`]->(b) "
                     "RETURN a.id AS source, b.id AS target, "
                     (if position? "r.position" "NULL") " AS position, "
                     "'" table "' AS rel;")
           {:keys [rows truncated?]}
           (ladybug/query-on-connection! conn stmt :max-rows export-max-rows)]
       (-> acc
           (update :edges into
                   (map (fn [[source target position rel]]
                          (cond-> {:source (str source)
                                   :target (str target)
                                   :rel    (str rel)}
                            (some? position) (assoc :position position))))
                   rows)
           (update :truncated? #(or % truncated?)))))
   {:edges [] :truncated? false}
   (rel-tables conn)))

(defn- bm-usage-bytes
  "Buffer-manager memory in use by this session's in-memory database
  (`CALL bm_info()` → [mem_limit mem_usage]); nil if the call fails."
  [conn]
  (ex/ignoring
   (-> (ladybug/query-on-connection! conn "CALL bm_info() RETURN *;" :max-rows 1)
       :rows first second)))

(defn export-graph-data!
  "Export the node/edge inventory of the in-memory graph for `profile-id`
  as plain data for the debug graph view. Returns nil when no session is
  loaded. Queries the Ladybug database (not the sync index) so the view
  reflects actual DB state, including drift."
  [profile-id]
  (when-let [{:keys [conn lock file-id index]} (get @sessions (session-key profile-id))]
    (locking lock
      (let [{:keys [nodes] nodes-truncated? :truncated?} (export-nodes conn)
            {:keys [edges] edges-truncated? :truncated?} (export-edges conn)]
        {:file-id   (str file-id)
         :revn      (:revn index)
         :truncated (boolean (or nodes-truncated? edges-truncated?))
         :bm-bytes  (bm-usage-bytes conn)
         :nodes     nodes
         :edges     edges}))))

(defn- delete-tree!
  [^java.io.File file]
  (when (.exists file)
    (doseq [f (reverse (file-seq file))]
      (.delete ^java.io.File f))))

(defn export-session-database!
  "Materialize the in-memory session graph of `profile-id` as a `.lbug` file.

  The console's graph is in-memory and live-synced, so it can differ from a
  fresh projection of the same file — which is exactly when someone wants to
  take it away and query it elsewhere. There is no \"save this database\"
  primitive, so the transfer goes through Ladybug's `EXPORT DATABASE` (Parquet
  per table) into a fresh on-disk database via `IMPORT DATABASE`.

  Note the round trip drops table comments. Nothing in the graph is addressed
  by a table comment: every table is resolved by name, so the loss costs
  nothing.

  Returns the path of the written database, or nil when no session is loaded.
  The caller owns the file and must delete it once streamed."
  [profile-id]
  (when-let [{:keys [conn lock file-id]} (get @sessions (session-key profile-id))]
    (let [stamp       (System/nanoTime)
          staging     (io/file (System/getProperty "java.io.tmpdir")
                               (str "penpot-graph-session-" file-id "-" stamp))
          db-path     (str (io/file (System/getProperty "java.io.tmpdir")
                                    (str file-id "-session-" stamp ".lbug")))]
      (try
        (locking lock
          (ladybug/exec-on-connection!
           conn [(str "EXPORT DATABASE '" (.getAbsolutePath staging)
                      "' (format='parquet');")]))
        (ladybug/with-connection! db-path
          (fn [target]
            (ladybug/exec-on-connection!
             target [(str "IMPORT DATABASE '" (.getAbsolutePath staging) "';")
                     "CHECKPOINT;"])))
        db-path
        (finally
          (delete-tree! staging))))))

(defn- hide-filter-columns
  "Drop `filter_*` columns from a query result before HTML table render;
  they exist to feed node ids to the graph-view filter, not for reading.
  The JSON response path keeps the full result."
  [{:keys [columns rows] :as result}]
  (let [idxs (vec (keep-indexed
                   (fn [i c] (when-not (str/starts-with? (str c) "filter_") i))
                   columns))]
    (if (or (empty? idxs) (= (count idxs) (count columns)))
      result
      (assoc result
             :columns (mapv (vec columns) idxs)
             :rows    (mapv (fn [row] (mapv (vec row) idxs)) rows)))))

(defn console-context
  "Build template data for the graph debug console page."
  [profile-id & {:keys [query query-result error message]}]
  {:session       (session-info profile-id)
   :query         (or query default-query)
   :query-result  (some-> query-result hide-filter-columns)
   :error         error
   :message       message
   :default-query default-query})
