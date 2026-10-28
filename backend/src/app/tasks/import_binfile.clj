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
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.storage :as sto]
   [datoteka.fs :as fs]
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

(def schema:create-params
  "The business params of an import as the creation command receives
  them: where the package goes and its name. The version is optional
  because the command reads it from the package header when the caller
  does not say it, and the manifest metadata kept for audit is filled by
  the command, never sent by the caller."
  [:map {:title "import-binfile-create-params" :closed true}
   [:project-id ::sm/uuid]
   [:name       [:or [:string {:max 250}]
                 [:map-of ::sm/uuid [:string {:max 250}]]]]
   [:version    {:optional true} [:enum 1 3]]])

(def schema:candidate
  "A library of the instance whose slugified name matches the one the package
  used and is therefore offered to the user as a replacement."
  [:map
   [:id           ::sm/uuid]
   [:name         :string]
   [:project-id   ::sm/uuid]
   [:project-name :string]])

(def schema:done-entry
  "A library of the package that was linked while it was read."
  [:map
   [:id        ::sm/uuid]
   [:name      :string]
   [:linked-to ::sm/uuid]])

(def schema:pending-entry
  "A library of the package that needs the user to choose a replacement:
  candidates are the libraries of the instance with the same slugified name
  (`bfc/find-shared-files-by-slug`)."
  [:map
   [:id         ::sm/uuid]
   [:name       :string]
   [:candidates [:vector schema:candidate]]])

(def schema:resolution-entry
  "The libraries one imported file uses, split in the ones already linked
  and the ones pending resolution."
  [:map
   [:id      ::sm/uuid]
   [:name    :string]
   [:done    [:vector schema:done-entry]]
   [:pending [:vector schema:pending-entry]]])

(def schema:resolution
  "The resolution of every imported file, keyed by its new file id. The key
  is a uuid, so the map needs this schema to come back typed: the result
  column is plain JSON, and without a decoder every key of the map would be
  read back as a keyword."
  [:map-of ::sm/uuid schema:resolution-entry])

(def schema:result
  "What the job stores when the import succeeds: the files it created and
  how the libraries of the package were resolved."
  [:map {:title "import-binfile-result" :closed true}
   [:file-ids   [:vector ::sm/uuid]]
   [:resolution {:optional true} schema:resolution]
   [:name       [:or [:string {:max 250}]
                 [:map-of ::sm/uuid [:string {:max 250}]]]]
   [:version    [:enum 1 3]]])

(def ^:private check-result
  (sm/check-fn schema:result
               :hint "invalid result of an import job"
               :type :validation
               :code :invalid-result))

(defn execute-import
  "Plain handler, importable and testable without integrant.

  The edition permission is checked again here, and not only when the job
  was created: it can be revoked while the job waits in its queue. The
  core runs inside a transaction, so a cancellation detected between units
  rolls the whole import back, and the result is checked against its schema
  inside that same transaction: a result the contract does not accept rolls
  the import back instead of leaving half of a file imported. The substrate
  checks the result again when the job is completed, which is the only net
  for a job that does not validate its own result.

  The package is the input of the job: it is released and its temporary
  copy deleted once the job is terminal, whatever the outcome. A crash is
  covered by the storage GC."
  [cfg context params]
  (let [profile-id (:profile-id context)
        project-id (:project-id params)
        input      (js/load-input cfg context)]

    (try
      (projects/check-edition-permissions! cfg profile-id project-id)

      (let [team   (or (teams/get-team cfg
                                       :profile-id profile-id
                                       :project-id project-id)
                       (ex/raise :type :not-found
                                 :code :project-not-found
                                 :hint "the destination project is not available"
                                 :profile-id profile-id
                                 :project-id project-id))
            result (db/tx-run! cfg
                               (fn [tx-cfg]
                                 ;; the core joins this transaction: it
                                 ;; is the one that decides whether the
                                 ;; whole import is kept or rolled back
                                 (check-result
                                  (assoc (bfj/import-files tx-cfg context
                                                           {:profile-id profile-id
                                                            :project-id project-id
                                                            :team       team
                                                            :name       (:name params)
                                                            :input      input
                                                            :version    (:version params)})
                                         :name    (:name params)
                                         :version (:version params)))))]

        result)

      (finally
        (fs/delete input)
        (js/release-input cfg context)))))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name          :import-binfile
   ::jobs/family        :import
   ::jobs/resource-role :input
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [context params]
                          (execute-import cfg context params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)
   ;; The resolution is keyed by file id: without the result schema the
   ;; keys come back as keywords and the dashboard wizard can no longer
   ;; match them with the file ids it holds. The substrate compiles the
   ;; decoder from it and checks the result against it before storing it.
   ::jobs/result-schema schema:result})
