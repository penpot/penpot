;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.debug
  "In-memory overlay sessions for the debug graph console.

  A session is a datascript overlay of one file
  (`app.graph.overlay`), maintained from the msgbus file-change feed by
  `app.graph.overlay.sync` and queried in Datalog through
  `app.graph.overlay.console`. The Ladybug engine no longer appears on
  this path: it keeps the batch-export tier (`app.graph.ingest`), where
  columnar output is the point. The overlay is immutable data in an atom,
  so readers never lock and the sync loop is the only writer."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.console :as console]
   [app.graph.overlay.queries :as queries]
   [app.graph.overlay.sync :as overlay.sync]
   [app.msgbus :as mbus]
   [app.srepl.helpers :as h]
   [clojure.string :as str]
   [datascript.core :as d]
   [promesa.exec.csp :as sp]))

(set! *warn-on-reflection* true)

(def default-query
  "Default console query, written to be self-explanatory in the textarea.
  The `filter_*` columns carry node ids for the graph-view result filter;
  the results table hides them (see `hide-filter-columns` and the
  template's `renderQueryOutput`). `$` is bound to the overlay and `%` to
  the shared rule set of `app.graph.overlay.queries`."
  (str "[:find ?component ?instance ?page\n"
       "       ?filter_src_id ?filter_tgt_id\n"
       " :in $ %\n"
       " :where\n"
       " (instance-of ?s ?c)\n"
       " [?c :component/name ?component]\n"
       " [?s :shape/name ?instance]\n"
       " [?s :shape/container ?pc]\n"
       " [?pc :container/name ?page]\n"
       " ;; filter_* columns omitted from the table; they drive the graph view\n"
       " [(identity ?s) ?filter_src_id]\n"
       " [(identity ?c) ?filter_tgt_id]]"))

(defonce ^:private sessions
  (atom {}))

(defn- session-key
  [profile-id]
  (str profile-id))

(defn- destroy-session!
  [{:keys [sync-ch msgbus]}]
  (when sync-ch
    (sp/close! sync-ch)
    (when msgbus
      (mbus/purge! msgbus [sync-ch]))))

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
  [profile-id {:keys [changes revn file-id]}]
  (try
    (when-let [current (get @sessions (session-key profile-id))]
      (when (= file-id (:file-id current))
        (let [result  (overlay.sync/apply-changes @(:db-atom current) changes)
              sync-at (ct/now)]
          (reset! (:db-atom current) (:db result))
          (swap! sessions update-in [(session-key profile-id) :meta]
                 (fn [meta]
                   (cond-> (-> meta
                               (update :sync dissoc :error)
                               (assoc-in [:sync :last-at] sync-at)
                               (assoc-in [:sync :last-applied] (:applied result))
                               (assoc-in [:sync :last-skipped] (:skipped result)))
                     (seq (:applied result))
                     (assoc :graph-revn (long revn)))))
          (when (seq (:skipped result))
            (l/dbg :hint "graph sync skipped changes"
                   :file-id (str file-id)
                   :revn revn
                   :skipped (:skipped result))))))
    (catch Throwable cause
      (l/wrn :hint "graph sync failed"
             :file-id (str file-id)
             :cause cause)
      (swap! sessions assoc-in [(session-key profile-id) :meta :sync :error]
             (ex-message cause)))))

(defn- start-sync-loop!
  [{:keys [profile-id file-id] :as session}]
  (if-let [msgbus (:msgbus session)]
    (let [sync-ch (sp/chan :buf (sp/dropping-buffer 64))]
      (mbus/sub! msgbus :topic file-id :chan sync-ch)
      ;; Recur ONLY while the channel is open. A bare `(recur)` after
      ;; `take!` returns nil would spin forever across every Load.
      (sp/go-loop []
        (when-let [message (sp/take! sync-ch)]
          (when (= :file-change (:type message))
            (apply-file-change! profile-id message))
          (recur)))
      (assoc session :sync-ch sync-ch))
    session))

(defn session-info
  "Return a public view of the current session for `profile-id`, if any."
  [profile-id]
  (when-let [{:keys [file-id meta loaded-at]} (get @sessions (session-key profile-id))]
    {:file-id        file-id
     :name           (:name meta)
     :revn           (:revn meta)
     :graph-revn     (:graph-revn meta)
     :schema-version (:schema-version meta)
     :projection     (:projection meta)
     :sync           (:sync meta)
     :loaded-at      (ct/format-inst loaded-at :iso)}))

(defn sync-status
  "Return incremental sync status for the active session."
  [profile-id]
  (when-let [session (get @sessions (session-key profile-id))]
    (let [{:keys [file-id meta loaded-at]} session]
      {:file-id    file-id
       :revn       (:revn meta)
       :graph-revn (:graph-revn meta)
       :sync       (:sync meta)
       :loaded-at  (ct/format-inst loaded-at :iso)})))

(defn unload-session!
  "Discard the in-memory overlay for `profile-id`."
  [profile-id]
  (when-let [session (get @sessions (session-key profile-id))]
    (destroy-session! session))
  (swap! sessions dissoc (session-key profile-id)))

(defn- fetch-file!
  [system file-id]
  (let [file-id (h/parse-uuid file-id)
        file    (db/run! system #(bfc/get-file % file-id :realize? true))]
    (when-not file
      (ex/raise :type :not-found
                :code :file-not-found
                :hint "file not found"
                :file-id (str file-id)))
    (when-not (:data file)
      (ex/raise :type :internal
                :code :file-without-data
                :hint "file has no data blob"
                :file-id (str file-id)))
    [file-id file]))

(defn load-session!
  "Build the overlay of `file-id` for `profile-id`."
  [cfg profile-id file-id]
  (unload-session! profile-id)
  (let [msgbus         (::mbus/msgbus cfg)
        [file-id file] (fetch-file! cfg file-id)
        started        (System/nanoTime)
        db             (overlay/build (:data file) file)
        build-ms       (/ (- (System/nanoTime) started) 1e6)
        stats          (assoc (queries/stats db)
                              :edges (queries/edge-counts db)
                              :build-ms (long build-ms))
        meta           {:name           (:name file)
                        :revn           (:revn file)
                        :graph-revn     (:revn file)
                        :schema-version overlay/schema-version
                        :projection     {:stats stats}}
        session        (-> {:db-atom    (atom db)
                            :file-id    file-id
                            :meta       meta
                            :msgbus     msgbus
                            :profile-id profile-id
                            :loaded-at  (ct/now)}
                           start-sync-loop!)]
    (swap! sessions assoc (session-key profile-id) session)
    meta))

(defn session-db
  "The current overlay value for `profile-id`, or nil."
  [profile-id]
  (some-> (get @sessions (session-key profile-id)) :db-atom deref))

(defn query-session!
  "Run a Datalog `statement` against the overlay for `profile-id`.

  Read-only by construction — `d/q` cannot transact — and gated against
  function smuggling by `app.graph.overlay.console/check-query!`."
  [profile-id statement]
  (when (or (nil? statement) (= "" statement))
    (ex/raise :type :validation
              :code :missing-query
              :hint "datalog query is required"))
  (if-let [db (session-db profile-id)]
    (-> (console/run-query db statement)
        format-query-result)
    (ex/raise :type :not-found
              :code :graph-session-not-loaded
              :hint "load a file graph before running queries")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; graph-view export
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity-node
  "Node payload for the G6 view. Node ids are datascript eids, which keeps
  two shapes sharing one uuid in different containers distinct — the
  Cypher export merged them."
  [db eid]
  (let [e (d/entity db eid)]
    (cond
      (:document/id e)
      {:id (str eid) :label (or (:document/name e) (str (:document/id e))) :table "Document"}

      (:component/id e)
      {:id (str eid) :label (or (:component/name e) (str (:component/id e))) :table "Component"}

      (:container/id e)
      {:id (str eid) :label (or (:container/name e) (str (:container/id e))) :table "Page"}

      (:shape/id e)
      {:id    (str eid)
       :label (or (:shape/name e) (str (:shape/id e)))
       :table (get overlay/shape-type->table (:shape/type e) "Shape")}

      (:color/id e)
      {:id (str eid) :label (or (:color/name e) (str (:color/id e))) :table "Color"}

      (:typography/id e)
      {:id (str eid) :label (or (:typography/name e) (str (:typography/id e))) :table "Typography"}

      (:token-set/id e)
      {:id (str eid) :label (or (:token-set/name e) (str (:token-set/id e))) :table "TokenSet"}

      (:token/id e)
      {:id (str eid) :label (:token/name e) :table "Token"})))

(defn- ref-edges
  [db attr rel]
  (map (fn [dtm] {:source (str (:e dtm)) :target (str (:v dtm)) :rel rel})
       (d/datoms db :avet attr)))

(defn- rule-edges
  [db rule rel]
  (map (fn [[s t]] {:source (str s) :target (str t) :rel rel})
       (d/q (into [] (concat '[:find ?s ?t :in $ % :where] [(list rule '?s '?t)]))
            db queries/rules)))

(defn- token-use-edges
  "UsesToken edges from the folded token attributes, joined to token
  entities by name (one edge per matching token when names repeat across
  sets, the same join the `uses-token` rule performs)."
  [db]
  (for [attr  overlay/token-attrs
        dtm   (d/datoms db :aevt attr)
        token (map :e (d/datoms db :avet :token/name (:v dtm)))]
    {:source (str (:e dtm))
     :target (str token)
     :rel    "UsesToken"}))

(defn export-graph-data!
  "Export the node/edge inventory of the overlay for `profile-id` as plain
  data for the debug graph view. Returns nil when no session is loaded."
  [profile-id]
  (when-let [{:keys [db-atom file-id meta]} (get @sessions (session-key profile-id))]
    (let [db    @db-atom
          eids  (into (sorted-set)
                      (mapcat #(map :e (d/datoms db :avet %)))
                      [:document/id :container/id :component/id :shape/id
                       :color/id :typography/id :token-set/id :token/id])
          nodes (into [] (keep #(entity-node db %)) eids)
          edges (-> []
                    (into (ref-edges db :shape/parent "IsChildOf"))
                    (into (ref-edges db :container/document "IsChildOf"))
                    (into (ref-edges db :component/document "IsChildOf"))
                    (into (ref-edges db :color/document "IsChildOf"))
                    (into (ref-edges db :typography/document "IsChildOf"))
                    (into (ref-edges db :token-set/document "IsChildOf"))
                    (into (ref-edges db :token/set "IsChildOf"))
                    (into (rule-edges db 'instance-of "IsInstanceOf"))
                    (into (rule-edges db 'refers-to "RefersTo"))
                    (into (rule-edges db 'fills-swap-slot "FillsSwapSlot"))
                    (into (ref-edges db :shape/fill-color "UsesColor"))
                    (into (ref-edges db :shape/stroke-color "UsesColor"))
                    (into (ref-edges db :shape/text-color "UsesColor"))
                    (into (ref-edges db :shape/uses-typography "UsesTypography"))
                    (into (token-use-edges db)))
          ;; component containers appear in both the component and the
          ;; container eid sweeps; the sorted set already deduplicates,
          ;; and `entity-node` classifies them as Component.
          edges (into [] (distinct) edges)]
      {:file-id   (str file-id)
       :revn      (:graph-revn meta)
       :truncated false
       :datoms    (count db)
       :nodes     nodes
       :edges     edges})))

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
