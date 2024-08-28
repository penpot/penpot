;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.binfile
  (:refer-clojure :exclude [assert])
  (:require
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.http.sse :as sse]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.doc :as-alias doc]
   [app.tasks.file-gc]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [promesa.exec :as px]
   [ring.response :as rres]))

(set! *warn-on-reflection* true)

;; --- Command: export-binfile

(def ^:private
  schema:export-binfile
  [:map {:title "export-binfile"}
   [:name [:string {:max 250}]]
   [:file-id ::sm/uuid]
   [:version {:optional true} :int]
   [:include-libraries ::sm/boolean]
   [:embed-assets ::sm/boolean]])

(defn stream-export-v1
  [cfg {:keys [file-id include-libraries embed-assets] :as params}]
  (reify rres/StreamableResponseBody
    (-write-body-to-stream [_ _ output-stream]
      (try
        (-> cfg
            (assoc ::bf.v1/ids #{file-id})
            (assoc ::bf.v1/embed-assets embed-assets)
            (assoc ::bf.v1/include-libraries include-libraries)
            (bf.v1/export-files! output-stream))
        (catch Throwable cause
          (l/err :hint "exception on exporting file"
                 :file-id (str file-id)
                 :cause cause))))))

(defn stream-export-v3
  [cfg {:keys [file-id include-libraries embed-assets] :as params}]
  (reify rres/StreamableResponseBody
    (-write-body-to-stream [_ _ output-stream]
      (try
        (-> cfg
            (assoc ::bf.v3/ids #{file-id})
            (assoc ::bf.v3/embed-assets embed-assets)
            (assoc ::bf.v3/include-libraries include-libraries)
            (bf.v3/export-files! output-stream))
        (catch Throwable cause
          (l/err :hint "exception on exporting file"
                 :file-id (str file-id)
                 :cause cause))))))

(sv/defmethod ::export-binfile
  "Export a penpot file in a binary format."
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sm/result schema:export-binfile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id version file-id] :as params}]
  (files/check-read-permissions! pool profile-id file-id)
  (fn [_]
    (let [version (or version 3)
          body    (case version
                    1 (stream-export-v1 cfg params)
                    2 (throw (ex-info "not-implemented" {}))
                    3 (stream-export-v3 cfg params))]

      {::rres/status 200
       ::rres/headers {"content-type" "application/octet-stream"}
       ::rres/body body})))

;; --- Command: import-binfile

(defn- import-binfile
  [{:keys [::wrk/executor ::bf.v1/project-id ::db/pool] :as cfg} input]
  ;; NOTE: the importation process performs some operations that
  ;; are not very friendly with virtual threads, and for avoid
  ;; unexpected blocking of other concurrent operations we
  ;; dispatch that operation to a dedicated executor.
  (let [result (px/invoke! executor (partial bf.v1/import-files! cfg input))]
    (db/update! pool :project
                {:modified-at (dt/now)}
                {:id project-id})
    result))

(def ^:private
  schema:import-binfile
  [:map {:title "import-binfile"}
   [:name [:string {:max 250}]]
   [:project-id ::sm/uuid]
   [:file ::media/upload]])

(sv/defmethod ::import-binfile
  "Import a penpot file in a binary format."
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sse/stream? true
   ::sm/params schema:import-binfile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id name project-id file] :as params}]
  (projects/check-read-permissions! pool profile-id project-id)
  (let [cfg (-> cfg
                (assoc ::bf.v1/project-id project-id)
                (assoc ::bf.v1/profile-id profile-id)
                (assoc ::bf.v1/name name))]
    (with-meta
      (sse/response #(import-binfile cfg (:path file)))
      {::audit/props {:file nil}})))
