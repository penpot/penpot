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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
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
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.file-gc]
   [app.util.services :as sv]
   [app.worker :as-alias wrk]
   [datoteka.fs :as fs]))

(set! *warn-on-reflection* true)

;; --- Command: export-binfile

(def ^:private
  schema:export-binfile
  [:map {:title "export-binfile"}
   [:file-id ::sm/uuid]
   [:include-libraries ::sm/boolean]
   [:embed-assets ::sm/boolean]])

(defn- export-binfile
  [{:keys [::sto/storage] :as cfg} {:keys [file-id include-libraries embed-assets]}]
  (let [output  (tmp/tempfile*)]
    (try
      (-> cfg
          (assoc ::bfc/ids #{file-id})
          (assoc ::bfc/embed-assets embed-assets)
          (assoc ::bfc/include-libraries include-libraries)
          (bf.v3/export-files! output))

      (let [data   (sto/content output)
            object (sto/put-object! storage
                                    {::sto/content data
                                     ::sto/touched-at (ct/in-future {:minutes 60})
                                     :content-type "application/zip"
                                     :bucket "tempfile"})]

        (-> (cf/get :public-uri)
            (u/join "/assets/by-id/")
            (u/join (str (:id object)))))

      (finally
        (fs/delete output)))))

(sv/defmethod ::export-binfile
  "Export a penpot file in a binary format."
  {::doc/added "1.15"
   ::doc/changes [["2.12" "Remove version parameter, only one version is supported"]]
   ::webhooks/event? true
   ::sm/params schema:export-binfile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-read-permissions! pool profile-id file-id)
  (sse/response (partial export-binfile cfg params)))

;; --- Command: import-binfile

(defn- import-binfile
  [{:keys [::db/pool] :as cfg} {:keys [profile-id project-id version name file]}]
  (let [team   (teams/get-team pool
                               :profile-id profile-id
                               :project-id project-id)
        cfg    (-> cfg
                   (assoc ::bfc/features (cfeat/get-team-enabled-features cf/flags team))
                   (assoc ::bfc/project-id project-id)
                   (assoc ::bfc/profile-id profile-id)
                   (assoc ::bfc/name name)
                   (assoc ::bfc/input (:path file)))

        result (case (int version)
                 1 (bf.v1/import-files! cfg)
                 3 (bf.v3/import-files! cfg))]

    (db/update! pool :project
                {:modified-at (ct/now)}
                {:id project-id}
                {::db/return-keys false})

    result))

(def ^:private schema:import-binfile
  [:map {:title "import-binfile"}
   [:name [:or [:string {:max 250}]
           [:map-of ::sm/uuid [:string {:max 250}]]]]
   [:project-id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]
   [:version {:optional true} ::sm/int]
   [:file media/schema:upload]])

(sv/defmethod ::import-binfile
  "Import a penpot file in a binary format. If `file-id` is provided,
  an in-place import will be performed instead of creating a new file.

  The in-place imports are only supported for binfile-v3 and when a
  .penpot file only contains one penpot file.
  "
  {::doc/added "1.15"
   ::doc/changes ["1.20" "Add file-id param for in-place import"
                  "1.20" "Set default version to 3"]

   ::webhooks/event? true
   ::sse/stream? true
   ::sm/params schema:import-binfile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id version file-id file] :as params}]
  (projects/check-edition-permissions! pool profile-id project-id)
  (let [version  (or version 3)
        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :version version))

        cfg      (cond-> cfg
                   (uuid? file-id)
                   (assoc ::bfc/file-id file-id))

        manifest (case (int version)
                   1 nil
                   3 (bf.v3/get-manifest (:path file)))]

    (with-meta
      (sse/response (partial import-binfile cfg params))
      {::audit/props {:file nil
                      :file-id file-id
                      :generated-by (:generated-by manifest)
                      :referer (:referer manifest)}})))
