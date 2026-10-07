;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.import-binfile-test
  "The `:import-binfile` job: what it imports, who may ask for it and what
  it leaves behind when it fails."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as v1]
   [app.binfile.v3 :as v3]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(def ^:private fixture
  "A real penpot export, the same fixture the binfile tests read."
  "backend_tests/test_files/svg-attrs-camel-case.penpot")

(defn- fixture-path
  []
  (-> fixture io/resource jio/file))

(defn- import-fixture!
  "A file in the default project of the profile, imported from the
  fixture through the core."
  [profile]
  (-> th/*system*
      (assoc ::bfc/project-id (:default-project-id profile))
      (assoc ::bfc/profile-id (:id profile))
      (assoc ::bfc/team-id (:default-team-id profile))
      (assoc ::bfc/input (fixture-path))
      (v3/import-files!)))

(defn- export-to-v1!
  "A version 1 package of a file, the format the old exporter writes."
  [file-id]
  (let [output (tmp/tempfile* :suffix ".penpot")]
    (v1/export-files! (-> th/*system*
                          (assoc ::bfc/ids #{file-id})
                          (assoc ::bfc/include-libraries false)
                          (assoc ::bfc/embed-assets false))
                      output)
    output))

(defn- stage-package!
  "The package a job consumes, as its resource."
  [profile path]
  (js/put-resource th/*system* (:id profile)
                   {:content  (sto/content path)
                    :filename "package.penpot"
                    :mtype    "application/zip"}))

(defn- make-job!
  "A job row as the substrate writes it: running, owned by a profile and
  holding the package it consumes."
  [profile-id resource-id]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id           id
                         :name         "import-binfile"
                         :tenant       (cf/get :tenant)
                         :queue        "binfile"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  0
                         :retry-num    0
                         :status       "running"
                         :profile-id   profile-id
                         :resource-id  resource-id
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    id))

(defn- run-import
  "Run the import handler of a job, as the runner does."
  [job-id params]
  (jobs/invoke (-> th/*system*
                   (assoc ::jobs/name :import-binfile)
                   (assoc ::jobs/params params)
                   (assoc ::jobs/context
                          (jobs/make-context (jobs/get-job th/*system* job-id))))))

(defn- project-files
  [project-id]
  (th/db-exec! ["SELECT id FROM file WHERE project_id = ? AND deleted_at IS NULL"
                project-id]))

(defn- input-row
  [resource-id]
  (th/db-get :storage-object {:id resource-id} {::db/remove-deleted false}))

(defn- temp-files
  []
  (seq (.list (jio/file tmp/default-tmp-dir))))

(defn- project-modified-at
  [project-id]
  (:modified-at (th/db-get :project {:id project-id})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-import-handler-imports-the-package-of-its-job
  (let [profile  (th/create-profile* 1)
        staged   (stage-package! profile (fixture-path))
        job-id   (make-job! (:id profile) (:resource-id staged))
        result   (run-import job-id {:project-id (:default-project-id profile)
                                     :name       "imported"
                                     :version    3})]

    (t/testing "the handler answers with the files it created"
      (t/is (= 1 (count (:file-ids result))))
      (t/is (= "imported" (:name result)))
      (t/is (= 3 (:version result)))
      (t/testing "and the file is in the destination project"
        (t/is (= (set (:file-ids result))
                 (set (map :id (project-files (:default-project-id profile))))))))

    (t/testing "the package of the job is released once the job is terminal"
      (t/is (some? (:deleted-at (input-row (:resource-id staged))))))

    (t/testing "and its temporary copy is gone"
      (t/is (empty? (temp-files))))))

(t/deftest the-import-marks-the-destination-project-as-modified
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        staged     (stage-package! profile (fixture-path))
        job-id     (make-job! (:id profile) (:resource-id staged))
        before     (project-modified-at project-id)]

    (run-import job-id {:project-id project-id
                        :name       "imported"
                        :version    3})

    (t/testing "the project the import wrote into is not left looking untouched"
      (t/is (pos? (- (inst-ms (project-modified-at project-id))
                     (inst-ms before)))))))

(t/deftest the-import-handler-imports-a-version-1-package
  (let [profile  (th/create-profile* 1)
        file-id  (first (:file-ids (import-fixture! profile)))
        staged   (stage-package! profile (export-to-v1! file-id))
        job-id   (make-job! (:id profile) (:resource-id staged))
        result   (run-import job-id {:project-id (:default-project-id profile)
                                     :name       "imported-v1"
                                     :version    1})]

    (t/is (= 1 (count (:file-ids result))))
    (t/is (= 1 (:version result)))))

(t/deftest the-import-handler-revalidates-the-edit-permission
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        staged  (stage-package! other (fixture-path))
        ;; the package belongs to `other`, but the destination project
        ;; belongs to `owner`: an import there is not allowed
        job-id  (make-job! (:id other) (:resource-id staged))
        before  (project-files (:default-project-id owner))]

    (t/testing "a job that cannot edit the destination does not import it"
      (t/is (thrown? Throwable
                     (run-import job-id {:project-id (:default-project-id owner)
                                         :name       "imported"
                                         :version    3}))))

    (t/testing "nothing was written in the destination"
      (t/is (= before (project-files (:default-project-id owner)))))

    (t/testing "the package is released anyway: the job is terminal"
      (t/is (some? (:deleted-at (input-row (:resource-id staged))))))))

(t/deftest a-job-that-was-already-cancelled-never-imports
  (let [profile (th/create-profile* 1)
        staged  (stage-package! profile (fixture-path))
        job-id  (make-job! (:id profile) (:resource-id staged))
        before  (project-files (:default-project-id profile))]

    (th/db-update! :job {:status "cancelled"} {:id job-id})

    (t/testing "a cancelled job does not import anything"
      (t/is (thrown? Throwable
                     (run-import job-id {:project-id (:default-project-id profile)
                                         :name       "imported"
                                         :version    3})))
      (t/is (= before (project-files (:default-project-id profile)))))

    (t/testing "and the package is released"
      (t/is (some? (:deleted-at (input-row (:resource-id staged))))))))

(t/deftest an-import-cancelled-while-it-runs-leaves-nothing-behind
  (let [profile (th/create-profile* 1)
        staged  (stage-package! profile (fixture-path))
        job-id  (make-job! (:id profile) (:resource-id staged))
        before  (project-files (:default-project-id profile))]

    ;; the user cancels while the import is running: the first progress
    ;; report is a good moment for it, because the core is already
    ;; writing by then
    (with-redefs [jobs/heartbeat (fn [& _]
                                   (th/db-update! :job {:status "cancelled"} {:id job-id})
                                   1)]
      (t/is (thrown? Throwable
                     (run-import job-id {:project-id (:default-project-id profile)
                                         :name       "imported"
                                         :version    3}))))

    (t/testing "the import is rolled back: the destination keeps no files"
      (t/is (= before (project-files (:default-project-id profile)))))

    (t/testing "and the package is released anyway"
      (t/is (some? (:deleted-at (input-row (:resource-id staged))))))))

(t/deftest a-package-that-is-not-a-penpot-file-fails-without-writing
  (let [profile (th/create-profile* 1)
        staged  (stage-package! profile (fixture-path))
        job-id  (make-job! (:id profile) (:resource-id staged))
        before  (project-files (:default-project-id profile))]

    ;; the bytes of the resource are replaced by something that is not a
    ;; package at all: the core must fail instead of importing garbage
    (sto/del-object! (:app.storage/storage th/*system*) (:resource-id staged))
    (let [broken (js/put-resource th/*system* (:id profile)
                                  {:content  (sto/content "not a penpot package")
                                   :filename "package.penpot"
                                   :mtype    "application/zip"})]
      (th/db-update! :job {:resource-id (:resource-id broken)} {:id job-id})

      (t/is (thrown? Throwable
                     (run-import job-id {:project-id (:default-project-id profile)
                                         :name       "imported"
                                         :version    3})))

      (t/testing "nothing was imported"
        (t/is (= before (project-files (:default-project-id profile)))))

      (t/testing "and the broken package is released too"
        (t/is (some? (:deleted-at (input-row (:resource-id broken)))))))))
