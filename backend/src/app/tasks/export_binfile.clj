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
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [integrant.core :as ig]))

(def schema:params
  "Business params of an export: the files to export, frozen when the job
  was created, and how their libraries are handled."
  [:map {:title "export-binfile-params" :closed true}
   [:file-ids    [::sm/set ::sm/uuid]]
   [:export-type [::sm/one-of #{:include-libraries :merge-libraries
                                :detach-libraries :link-later}]]])

(defn execute-export
  "Plain handler, importable and testable without integrant.

  The artifact is left in a temporary file for the caller to store and
  clean up: uploading it to storage and completing with its descriptor is
  the next step of the job."
  [cfg context params]
  (let [output (tmp/tempfile* :suffix ".penpot")]
    (bfj/export-files cfg context {:ids         (:file-ids params)
                                   :export-type (:export-type params)
                                   :output      output})
    output))

(defmethod ig/assert-key ::export-binfile-job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided")
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics"))

(defmethod ig/init-key ::export-binfile-job-def
  [_ cfg]
  {::jobs/name          :export-binfile
   ::jobs/family        :export
   ::jobs/resource-role :output
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [context params]
                          (execute-export cfg context params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)})
