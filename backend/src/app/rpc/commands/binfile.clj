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
  (yres/stream-body
   (fn [_ output-stream]
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
    (let [version (or version 1)
          body    (case (int version)
                    1 (stream-export-v1 cfg params)
                    2 (throw (ex-info "not-implemented" {}))
                    3 (stream-export-v3 cfg params))]

      {::yres/status 200
       ::yres/headers {"content-type" "application/octet-stream"}
       ::yres/body body})))

;; --- Command: import-binfile

(defn- import-binfile-v1
  [{:keys [::wrk/executor] :as cfg} {:keys [project-id profile-id name file]}]
  (let [cfg (-> cfg
                (assoc ::bf.v1/project-id project-id)
                (assoc ::bf.v1/profile-id profile-id)
                (assoc ::bf.v1/name name)
                (assoc ::bf.v1/input (:path file)))]

    ;; NOTE: the importation process performs some operations that are
    ;; not very friendly with virtual threads, and for avoid
    ;; unexpected blocking of other concurrent operations we dispatch
    ;; that operation to a dedicated executor.
    (px/invoke! executor (partial bf.v1/import-files! cfg))))

(defn- import-binfile-v3
  [{:keys [::wrk/executor] :as cfg} {:keys [project-id profile-id name file]}]
  (let [cfg (-> cfg
                (assoc ::bf.v3/project-id project-id)
                (assoc ::bf.v3/profile-id profile-id)
                (assoc ::bf.v3/name name)
                (assoc ::bf.v3/input (:path file)))]
    ;; NOTE: the importation process performs some operations that are
    ;; not very friendly with virtual threads, and for avoid
    ;; unexpected blocking of other concurrent operations we dispatch
    ;; that operation to a dedicated executor.
    (px/invoke! executor (partial bf.v3/import-files! cfg))))

(defn- import-binfile
  [{:keys [::db/pool] :as cfg} {:keys [project-id version] :as params}]
  (let [result (case (int version)
                 1 (import-binfile-v1 cfg params)
                 3 (import-binfile-v3 cfg params))]
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
