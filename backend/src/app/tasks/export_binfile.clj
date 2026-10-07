;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.export-binfile
  "The `:export-binfile` job: it produces the `.penpot` artifact of the
  set of files frozen in its params."
  (:require
   [app.binfile.jobs :as bfj]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.metrics :as mtx]
   [app.rpc.commands.files :as files]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [datoteka.fs :as fs]
   [integrant.core :as ig]))

(def schema:params
  "Business params of an export: the files to export, frozen when the job
  was created, and how their libraries are handled."
  [:map {:title "export-binfile-params" :closed true}
   [:file-ids    [::sm/set ::sm/uuid]]
   [:export-type [::sm/one-of #{:include-libraries :merge-libraries
                                :detach-libraries :link-later}]]])

(def ^:private artifact
  "What the user sees of the package an export produces. A single name
  describes a set of files, so it does not come from any of them."
  {:filename "export.penpot"
   :mtype    "application/zip"})

(def schema:result
  "What the job stores when the export succeeds: where the artifact can be
  downloaded from and what it is."
  [:map {:title "export-binfile-result" :closed true}
   [:resource-uri ::sm/text]
   [:filename     ::sm/text]
   [:mtype        ::sm/text]
   [:size         ::sm/int]])

(def ^:private check-result
  (sm/check-fn schema:result
               :hint "invalid result of an export job"
               :type :validation
               :code :invalid-result))

(defn execute-export
  "Plain handler, importable and testable without integrant.

  The read permission is checked again here, and not only when the job was
  created: it can be revoked while the job waits in its queue. The artifact
  is stored as the resource of the job, and the completion envelope carries
  its descriptor as the business result and its id for the runner to
  associate."
  [cfg context params]
  (let [profile-id (:profile-id context)
        file-ids   (:file-ids params)]

    (doseq [file-id file-ids]
      (files/check-read-permissions! cfg profile-id file-id))

    (let [output (tmp/tempfile* :suffix ".penpot")]
      (try
        (bfj/export-files cfg context {:ids         file-ids
                                       :export-type (:export-type params)
                                       :output      output})
        (let [resource (js/put-resource cfg profile-id
                                        (assoc artifact :content (sto/content output)))]
          {:result      (check-result (dissoc resource :resource-id))
           :resource-id (:resource-id resource)})
        (finally
          (fs/delete output))))))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name          :export-binfile
   ::jobs/family        :export
   ::jobs/resource-role :output
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [context params]
                          (execute-export cfg context params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)})
