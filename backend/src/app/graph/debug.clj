;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.debug
  "In-memory Ladybug sessions for the debug graph console."
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.graph.ingest :as graph.ingest]
   [app.graph.ladybug :as ladybug]
   [clojure.string :as str])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.Database))

(set! *warn-on-reflection* true)

(def default-query
  "MATCH (n:Document) RETURN n.id AS id, n.name AS name;")

(defonce ^:private sessions
  (atom {}))

(defn- session-key
  [profile-id]
  (str profile-id))

(defn- destroy-session!
  [{:keys [conn db]}]
  (when conn
    (ex/ignoring (.close ^Connection conn)))
  (when db
    (ex/ignoring (.close ^Database db))))

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

(defn session-info
  "Return a public view of the current session for `profile-id`, if any."
  [profile-id]
  (when-let [{:keys [file-id meta loaded-at]} (get @sessions (session-key profile-id))]
    {:file-id        file-id
     :name           (:name meta)
     :revn           (:revn meta)
     :schema-version (:schema-version meta)
     :projection     (:projection meta)
     :loaded-at      (ct/format-inst loaded-at :iso)}))

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
        ^Connection conn (Connection. db)]
    (.setQueryTimeout conn 0)
    (try
      (let [meta (graph.ingest/ingest-on-connection! cfg conn file-id
                                                     :db-path ":memory:"
                                                     :skip-stats? true
                                                     :skip-validation? true)]
        (swap! sessions assoc (session-key profile-id)
               {:db db
                :conn conn
                :file-id file-id
                :meta meta
                :loaded-at (ct/now)})
        meta)
      (catch Throwable cause
        (destroy-session! {:conn conn :db db})
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
