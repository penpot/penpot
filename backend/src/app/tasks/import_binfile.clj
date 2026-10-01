;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.import-binfile
  "The `:import-binfile` job: it imports the package the job owns as a
  resource into the project frozen in its params."
  (:require
   [app.binfile.jobs :as bfj]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.metrics :as mtx]
   [app.rpc.commands.teams :as teams]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def schema:params
  "Business params of an import: where the package goes, its name, the
  format version and the manifest metadata kept for audit."
  [:map {:title "import-binfile-params" :closed true}
   [:project-id   ::sm/uuid]
   [:name         [:or [:string {:max 250}]
                   [:map-of ::sm/uuid [:string {:max 250}]]]]
   [:version      [:enum 1 3]]
   [:generated-by {:optional true} [:maybe ::sm/text]]
   [:referer      {:optional true} [:maybe ::sm/text]]])

(defn execute-import
  "Plain handler, importable and testable without integrant.

  The package is downloaded from the resource of the job to a temporary
  file. Releasing that resource and turning the result into the job result
  is the next step of the job."
  [cfg context params]
  (let [profile-id (:profile-id context)
        project-id (:project-id params)
        team       (or (teams/get-team cfg
                                       :profile-id profile-id
                                       :project-id project-id)
                       (ex/raise :type :not-found
                                 :code :project-not-found
                                 :hint "the destination project is not available"
                                 :profile-id profile-id
                                 :project-id project-id))
        input      (js/load-input cfg context)]

    (db/tx-run! cfg
                (fn [_]
                  (bfj/import-files cfg context {:profile-id profile-id
                                                 :project-id project-id
                                                 :team       team
                                                 :name       (:name params)
                                                 :input      input
                                                 :version    (:version params)})))))

(defmethod ig/assert-key ::import-binfile-job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics"))

(defmethod ig/init-key ::import-binfile-job-def
  [_ cfg]
  {::jobs/name          :import-binfile
   ::jobs/family        :import
   ::jobs/resource-role :input
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [context params]
                          (execute-import cfg context params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)})
