;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.binfile
  (:refer-clojure :exclude [assert])
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.http.sse :as sse]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.tasks.file-gc]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [promesa.exec :as px]
   [yetti.response :as yres]))

(set! *warn-on-reflection* true)

;; --- Command: export-binfile

(def ^:private
  schema:export-binfile
  [:map {:title "export-binfile"}
   [:name [:string {:max 250}]]
   [:file-id ::sm/uuid]
   [:version {:optional true} ::sm/int]
   [:include-libraries ::sm/boolean]
   [:embed-assets ::sm/boolean]])

(defn stream-export-v1
  [cfg {:keys [file-id include-libraries embed-assets] :as params}]
  (yres/stream-body
   (fn [_ output-stream]
     (try
       (-> cfg
           (assoc ::bfc/ids #{file-id})
           (assoc ::bfc/embed-assets embed-assets)
           (assoc ::bfc/include-libraries include-libraries)
           (bf.v1/export-files! output-stream))
       (catch Throwable cause
         (l/err :hint "exception on exporting file"
                :file-id (str file-id)
                :cause cause))))))

(defn stream-export-v3
  [cfg {:keys [file-id include-libraries embed-assets] :as params}]
  (yres/stream-body
   (fn [_ output-stream]
     (try
       (-> cfg
           (assoc ::bfc/ids #{file-id})
           (assoc ::bfc/embed-assets embed-assets)
           (assoc ::bfc/include-libraries include-libraries)
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
    (let [version (or version 1)
          body    (case (int version)
                    1 (stream-export-v1 cfg params)
                    2 (throw (ex-info "not-implemented" {}))
                    3 (stream-export-v3 cfg params))]

      {::yres/status 200
       ::yres/headers {"content-type" "application/octet-stream"}
       ::yres/body body})))

;; --- Command: import-binfile

(defn- import-binfile
  [{:keys [::db/pool ::wrk/executor] :as cfg} {:keys [profile-id project-id version name file]}]
  (let [team   (teams/get-team pool
                               :profile-id profile-id
                               :project-id project-id)
        cfg    (-> cfg
                   (assoc ::bfc/features (cfeat/get-team-enabled-features cf/flags team))
                   (assoc ::bfc/project-id project-id)
                   (assoc ::bfc/profile-id profile-id)
                   (assoc ::bfc/name name)
                   (assoc ::bfc/input (:path file)))

        ;; NOTE: the importation process performs some operations that are
        ;; not very friendly with virtual threads, and for avoid
        ;; unexpected blocking of other concurrent operations we dispatch
        ;; that operation to a dedicated executor.
        result (case (int version)
                 1 (px/invoke! executor (partial bf.v1/import-files! cfg))
                 3 (px/invoke! executor (partial bf.v3/import-files! cfg)))]

    (db/update! pool :project
                {:modified-at (dt/now)}
                {:id project-id})

    result))

(def ^:private schema:import-binfile
  [:map {:title "import-binfile"}
   [:name [:or [:string {:max 250}]
           [:map-of ::sm/uuid [:string {:max 250}]]]]
   [:project-id ::sm/uuid]
   [:version {:optional true} ::sm/int]
   [:file ::media/upload]])

(sv/defmethod ::import-binfile
  "Import a penpot file in a binary format."
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sse/stream? true
   ::sm/params schema:import-binfile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id version] :as params}]
  (projects/check-edition-permissions! pool profile-id project-id)
  (let [params (-> params
                   (assoc :profile-id profile-id)
                   (assoc :version (or version 1)))]
    (with-meta
      (sse/response (partial import-binfile cfg params))
      {::audit/props {:file nil}})))
