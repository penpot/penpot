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
   [app.graph.sync :as graph.sync]
   [app.msgbus :as mbus]
   [clojure.string :as str]
   [promesa.exec.csp :as sp])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.Database
   org.apache.arrow.memory.RootAllocator))

(set! *warn-on-reflection* true)

(def default-query
  "MATCH (n:Document) RETURN n.id AS id, n.name AS name;")

(defonce ^:private sessions
  (atom {}))

(defn- session-key
  [profile-id]
  (str profile-id))

(defn- close-arrow-alloc!
  [arrow-alloc]
  (when arrow-alloc
    (let [^RootAllocator alloc arrow-alloc
          outstanding (.getAllocatedMemory alloc)]
      (try
        (.close alloc)
        (catch Exception e
          (l/wrn :hint "arrow allocator close failed on session destroy"
                 :allocated-bytes outstanding
                 :cause e))))))

(defn- destroy-session!
  "Release session resources.

  Order matters for Arrow: close Ladybug Connection/Database first so any
  retained Arrow staging buffers are released, then close the RootAllocator."
  [{:keys [conn db sync-ch msgbus arrow-alloc]}]
  (when sync-ch
    (sp/close! sync-ch)
    (when msgbus
      (mbus/purge! msgbus [sync-ch])))
  (when conn
    (ex/ignoring (.close ^Connection conn)))
  (when db
    (ex/ignoring (.close ^Database db)))
  (close-arrow-alloc! arrow-alloc))

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
                    (let [result (graph.sync/apply-changes!
                                  conn (:index current) changes revn)
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
      (sp/go-loop []
        (when-let [message (sp/take! sync-ch)]
          (when (= :file-change (:type message))
            (apply-file-change! conn profile-id message)))
        (recur))
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
  "Ingest `file-id` into a new in-memory Ladybug database for `profile-id`.

  Uses Arrow by default. The Arrow RootAllocator is owned by the session and
  closed only after the Ladybug Database (on unload/reload); closing it while
  the connection is still open leaks direct memory on every load."
  [cfg profile-id file-id]
  (unload-session! profile-id)
  (let [^Database db (Database.)
        ^Connection conn (Connection. db)
        ^RootAllocator arrow-alloc (RootAllocator.)
        msgbus (::mbus/msgbus cfg)]
    (.setQueryTimeout conn 0)
    (ladybug/ensure-extensions! conn)
    (try
      (let [meta  (graph.ingest/ingest-on-connection! cfg conn file-id
                                                      :db-path ":memory:"
                                                      :skip-stats? true
                                                      :skip-validation? true
                                                      :use-arrow? true
                                                      :arrow-alloc arrow-alloc)
            index (graph.sync/build-index file-id (:revn meta) (:projection meta))
            meta  (update meta :projection select-keys [:stats])
            session
            (-> {:db db
                 :conn conn
                 :arrow-alloc arrow-alloc
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
        (destroy-session! {:conn conn :db db :arrow-alloc arrow-alloc :msgbus msgbus})
        (throw cause)))))

(defn query-session!
  "Run `statement` against the in-memory graph for `profile-id`."
  [profile-id statement]
  (when (str/blank? statement)
    (ex/raise :type :validation
              :code :missing-query
              :hint "cypher query is required"))
  (if-let [{:keys [conn]} (get @sessions (session-key profile-id))]
    (-> (ladybug/query-on-connection! conn statement)
        format-query-result)
    (ex/raise :type :not-found
              :code :graph-session-not-loaded
              :hint "load a file graph before running queries")))

(defn console-context
  "Build template data for the graph debug console page."
  [profile-id & {:keys [query query-result error message]}]
  {:session       (session-info profile-id)
   :query         (or query default-query)
   :query-result  query-result
   :error         error
   :message       message
   :default-query default-query})
