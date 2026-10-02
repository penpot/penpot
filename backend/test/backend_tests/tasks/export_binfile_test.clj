;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.export-binfile-test
  "The `:export-binfile` job: what it produces, who may ask for it and
  what it leaves behind when it fails."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(def ^:private fixture
  "A real penpot export, the same fixture the binfile tests read."
  "backend_tests/test_files/svg-attrs-camel-case.penpot")

(defn- get-storage
  []
  (assoc (:app.storage/storage th/*system*) ::sto/backend :fs))

(defn- import-fixture!
  "A file in the default project of the profile, imported from the
  fixture through the core."
  [profile]
  (-> th/*system*
      (assoc ::bfc/project-id (:default-project-id profile))
      (assoc ::bfc/profile-id (:id profile))
      (assoc ::bfc/team-id (:default-team-id profile))
      (assoc ::bfc/input (-> fixture io/resource jio/file))
      (v3/import-files!)))

(defn- make-job!
  "A job row as the substrate writes it: running and owned by a profile."
  [profile-id]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id           id
                         :name         "export-binfile"
                         :tenant       (cf/get :tenant)
                         :queue        "binfile"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  0
                         :retry-num    0
                         :status       "running"
                         :profile-id   profile-id
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    id))

(defn- run-export
  "Run the export handler of a job, as the runner does."
  [job-id params]
  (jobs/invoke (-> th/*system*
                   (assoc ::jobs/name :export-binfile)
                   (assoc ::jobs/params params)
                   (assoc ::jobs/context
                          (jobs/make-context (jobs/get-job th/*system* job-id))))))

(defn- count-storage-objects
  []
  (count (th/db-exec! ["SELECT id FROM storage_object"])))

(defn- temp-files
  "The temporary files the export leaves in the tmp directory."
  []
  (seq (.list (jio/file tmp/default-tmp-dir))))

(defn- download
  "The artifact as a local file, as a client would fetch it."
  [resource-id]
  (let [object (sto/get-object (get-storage) resource-id)
        path   (tmp/tempfile* :suffix ".penpot")]
    (with-open [out (io/output-stream path)]
      (io/copy (sto/get-object-data (get-storage) object) out))
    path))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-export-handler-stores-the-artifact-of-its-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile))
        result  (run-export job-id {:file-ids    #{file-id}
                                    :export-type :detach-libraries})
        object  (sto/get-object (get-storage) (:resource-id result))]

    (t/testing "the handler answers with the completion envelope"
      (t/is (uuid? (:resource-id result)))
      (t/is (= "export.penpot" (get-in result [:result :filename])))
      (t/is (= "application/zip" (get-in result [:result :mtype])))
      (t/is (pos? (get-in result [:result :size])))
      (t/is (str/includes? (get-in result [:result :resource-uri])
                           (str (:resource-id result)))))

    (t/testing "the artifact is a job resource owned by the profile of the job"
      (t/is (= sto/job-resource-bucket (:bucket (meta object))))
      (t/is (= (:id profile) (:profile-id (meta object)))))

    (t/testing "it is touched, so a crash before the completion leaves it reclaimable"
      (t/is (some? (:touched-at (th/db-get :storage-object
                                           {:id (:resource-id result)})))))

    (t/testing "the package is the one the core produces for those files"
      (let [manifest (v3/get-manifest th/*system* (download (:resource-id result)))]
        (t/is (= [file-id] (mapv :id (:files manifest))))))

    (t/testing "the completion of the job associates the artifact"
      (jobs/complete th/*system*
                     :job-id job-id
                     :result (:result result)
                     :resource-id (:resource-id result))
      (t/is (= (:resource-id result)
               (:resource-id (jobs/get-job th/*system* job-id)))))))

(t/deftest the-export-handler-revalidates-the-read-permission
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        file-id (first (:file-ids (import-fixture! owner)))
        job-id  (make-job! (:id other))
        before  (count-storage-objects)]

    (t/testing "a job whose owner cannot read a file does not export it"
      (t/is (thrown? Throwable
                     (run-export job-id {:file-ids    #{file-id}
                                         :export-type :detach-libraries}))))

    (t/testing "and nothing was stored for it"
      (t/is (= before (count-storage-objects)))
      (t/is (nil? (:resource-id (jobs/get-job th/*system* job-id)))))

    (t/testing "the temporary file is not left behind either"
      (t/is (empty? (temp-files))))))

(t/deftest the-export-handler-leaves-nothing-when-the-storage-fails
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile))
        before  (count-storage-objects)]

    (with-redefs [sto/put-object! (fn [& _]
                                    (ex/raise :type :internal
                                              :code :boom
                                              :hint "storage is down"))]
      (t/is (thrown? Throwable
                     (run-export job-id {:file-ids    #{file-id}
                                         :export-type :detach-libraries}))))

    (t/testing "the job never comes to own an artifact"
      (t/is (nil? (:resource-id (jobs/get-job th/*system* job-id)))))

    (t/testing "no artifact was stored"
      (t/is (= before (count-storage-objects))))

    (t/testing "and the temporary file of the export is gone"
      (t/is (empty? (temp-files))))))

(t/deftest the-export-handler-leaves-nothing-behind-when-the-job-is-cancelled
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile))
        before  (count-storage-objects)]

    (th/db-update! :job {:status "cancelled"} {:id job-id})

    (t/is (thrown? Throwable
                   (run-export job-id {:file-ids    #{file-id}
                                       :export-type :detach-libraries})))

    (t/testing "no artifact was stored"
      (t/is (= before (count-storage-objects))))

    (t/testing "and the temporary file of the export was cleaned up"
      (t/is (empty? (temp-files))))))
