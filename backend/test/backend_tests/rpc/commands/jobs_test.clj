;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc.commands.jobs-test
  "The creation of a user-facing job: what it answers, what it freezes and
  what it refuses before anything is stored."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.quotes :as-alias quotes]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(def ^:private fixture
  "A real penpot export, the same fixture the binfile tests read."
  "backend_tests/test_files/svg-attrs-camel-case.penpot")

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

(defn- create-export-job!
  [profile-id file-ids & {:as extra}]
  (th/command! (merge {::th/type       :create-export-job
                       ::rpc/profile-id profile-id
                       :name           :export-binfile
                       :params         {:file-ids    file-ids
                                        :export-type :detach-libraries}}
                      extra)))

(defn- count-jobs
  []
  (count (th/db-exec! ["SELECT id FROM job"])))

(defn- count-storage-objects
  []
  (count (th/db-exec! ["SELECT id FROM storage_object"])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest create-export-job-answers-with-a-pending-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        before  (count-storage-objects)
        out     (create-export-job! (:id profile) #{file-id})]

    (t/is (th/success? out))

    (let [result (:result out)
          job    (jobs/get-job th/*system* (:id result))]

      (t/testing "the answer is enough to follow the job"
        (t/is (uuid? (:id result)))
        (t/is (= "pending" (:status result)))
        (t/is (= "export-binfile" (:name result)))
        (t/is (ct/inst? (:created-at result)))
        (t/is (pos? (- (inst-ms (:expires-at result)) (inst-ms (ct/now))))))

      (t/testing "and the row freezes what the job will do"
        (let [job-def (jobs/get-job-def (::jobs/defs th/*system*) :export-binfile)
              params  (jobs/decode-params job-def (:params job))]
          (t/is (= "new" (:status job)))
          (t/is (= "binfile" (:queue job)))
          (t/is (= 0 (:max-retries job)))
          (t/is (= (:id profile) (:profile-id job)))
          (t/is (= #{file-id} (:file-ids params)))
          (t/is (= :detach-libraries (:export-type params)))))

      (t/testing "creating a job does not run it"
        (t/is (= before (count-storage-objects)))))))

(t/deftest create-export-job-requires-at-least-one-file
  (let [profile (th/create-profile* 1)
        out     (create-export-job! (:id profile) #{})]

    (t/is (not (th/success? out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :no-files-to-export (th/ex-code (:error out))))
    (t/testing "and nothing was created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-job-checks-the-read-permission
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        file-id (first (:file-ids (import-fixture! owner)))
        out     (create-export-job! (:id other) #{file-id})]

    (t/testing "a file the caller cannot read is not exported"
      (t/is (not (th/success? out)))
      (t/is (= :not-found (th/ex-type (:error out)))))

    (t/testing "and no job was created for it"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-job-refuses-a-name-that-is-not-an-export-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (create-export-job! (:id profile) #{file-id} :name :import-binfile)]

    (t/testing "the registry decides which names are export jobs"
      (t/is (not (th/success? out)))
      (t/is (= :validation (th/ex-type (:error out))))
      (t/is (= :not-a-job-of-the-family (th/ex-code (:error out)))))

    (t/testing "and nothing was created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-job-enforces-the-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-export-jobs-per-profile 1})}]

    (let [profile (th/create-profile* 1)
          file-id (first (:file-ids (import-fixture! profile)))]

      (t/testing "the first job fits in the quote"
        (t/is (th/success? (create-export-job! (:id profile) #{file-id})))
        (t/is (= 1 (count-jobs))))

      (t/testing "the one that would go over it is refused"
        (let [out (create-export-job! (:id profile) #{file-id})]
          (t/is (not (th/success? out)))
          (t/is (= :restriction (th/ex-type (:error out))))
          (t/is (= :max-quote-reached (th/ex-code (:error out))))
          (t/is (= "export-jobs-per-profile" (:target (ex-data (:error out)))))))

      (t/testing "so the quote is what stopped the second job"
        (t/is (= 1 (count-jobs)))))))

(t/deftest create-export-job-adds-the-job-id-to-the-audit-props
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (create-export-job! (:id profile) #{file-id})
        props   (::audit/props (meta (:result out)))]

    (t/testing "the audit of the call points at the job, not at its files"
      (t/is (= (:id (:result out)) (:job-id props)))
      (t/is (= 1 (:files props)))
      (t/is (= "detach-libraries" (:export-type props)))
      (t/is (= #{:job-id :files :export-type} (set (keys props)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- upload-file
  "The package as an upload the http layer would have stored: a file on
  disk and its metadata."
  []
  (let [source (-> fixture io/resource jio/file)]
    {:filename "package.penpot"
     :path     (th/tempfile fixture)
     :mtype    "application/zip"
     :size     (.length source)}))

(defn- upload-chunked!
  "Upload the fixture as a single chunk and return the session id."
  [profile]
  (let [session-id (-> (th/command! {::th/type       :create-upload-session
                                     ::rpc/profile-id (:id profile)
                                     :total-chunks  1})
                       :result :session-id)
        out        (th/command! {::th/type        :upload-chunk
                                 ::rpc/profile-id (:id profile)
                                 :session-id    session-id
                                 :index         0
                                 :content       (upload-file)})]
    (t/is (nil? (:error out)))
    session-id))

(defn- create-import-job!
  [profile-id project-id & {:as extra}]
  (th/command! (merge {::th/type       :create-import-job
                       ::rpc/profile-id profile-id
                       :name           :import-binfile
                       :params         {:project-id project-id
                                        :name       "imported"}}
                      extra)))

(defn- session-row
  [session-id]
  (th/db-get :upload-session {:id session-id} {::db/remove-deleted false}))

(defn- live-storage-objects
  []
  (count (th/db-exec! ["SELECT id FROM storage_object WHERE deleted_at IS NULL"])))

(defn- assembled-tempfiles
  "The temporary files the chunk assembly leaves behind. The copies this
  test uploads are its own business."
  []
  (->> (.list (jio/file tmp/default-tmp-dir))
       (filter #(str/starts-with? % "penpot.chunked-upload."))
       (seq)))

(t/deftest create-import-job-answers-with-a-pending-job
  (let [profile (th/create-profile* 1)
        out     (create-import-job! (:id profile) (:default-project-id profile)
                                    :file (upload-file))]

    (t/is (th/success? out))

    (let [result (:result out)
          job    (jobs/get-job th/*system* (:id result))
          staged (sto/get-object (:app.storage/storage th/*system*) (:resource-id job))]

      (t/testing "the answer is enough to follow the job"
        (t/is (uuid? (:id result)))
        (t/is (= "pending" (:status result)))
        (t/is (= "import-binfile" (:name result))))

      (t/testing "the row points at the package the job will consume"
        (t/is (some? (:resource-id job)))
        (t/is (= "binfile" (:queue job)))
        (t/is (= sto/job-resource-bucket (:bucket (meta staged))))
        (t/is (= (:id profile) (:profile-id (meta staged)))))

      (t/testing "and freezes the destination and the manifest metadata"
        (let [job-def (jobs/get-job-def (::jobs/defs th/*system*) :import-binfile)
              params  (jobs/decode-params job-def (:params job))]
          (t/is (= (:default-project-id profile) (:project-id params)))
          (t/is (= "imported" (:name params)))
          (t/is (= 3 (:version params)))
          (t/is (string? (:generated-by params)))))

      (t/testing "the audit points at the job and the manifest, not the package"
        (let [props (::audit/props (meta (:result out)))]
          (t/is (= (:id result) (:job-id props)))
          (t/is (= #{:job-id :generated-by :referer} (set (keys props)))))))))

(t/deftest create-import-job-assembles-the-chunks-of-its-upload
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        out        (create-import-job! (:id profile) (:default-project-id profile)
                                       :upload-id session-id)]

    (t/is (th/success? out))

    (t/testing "the assembled package is the resource of the job"
      (let [job (jobs/get-job th/*system* (:id (:result out)))]
        (t/is (some? (:resource-id job)))))

    (t/testing "the upload session is consumed"
      (t/is (some? (:deleted-at (session-row session-id)))))

    (t/testing "and the temporary file of the assembly is gone"
      (t/is (empty? (assembled-tempfiles))))))

(t/deftest create-import-job-rejects-an-incomplete-upload
  (let [profile    (th/create-profile* 1)
        session-id (-> (th/command! {::th/type       :create-upload-session
                                     ::rpc/profile-id (:id profile)
                                     :total-chunks  2})
                       :result :session-id)
        out        (create-import-job! (:id profile) (:default-project-id profile)
                                       :upload-id session-id)]

    (t/is (not (th/success? out)))
    (t/is (= :missing-chunks (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))))

(t/deftest create-import-job-refuses-an-upload-of-another-profile
  (let [owner      (th/create-profile* 1)
        other      (th/create-profile* 2)
        session-id (upload-chunked! owner)
        out        (create-import-job! (:id other) (:default-project-id other)
                                       :upload-id session-id)]

    (t/testing "the upload belongs to the profile that made it"
      (t/is (not (th/success? out)))
      (t/is (= :not-found (th/ex-type (:error out)))))

    (t/testing "and it is not consumed by the attempt"
      (t/is (nil? (:deleted-at (session-row session-id)))))

    (t/testing "nor is any job created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-import-job-checks-the-edition-permission
  (let [owner      (th/create-profile* 1)
        other      (th/create-profile* 2)
        session-id (upload-chunked! other)
        out        (create-import-job! (:id other) (:default-project-id owner)
                                       :upload-id session-id)]

    (t/testing "a job that cannot edit the destination is refused"
      (t/is (not (th/success? out))))

    (t/testing "before the upload is assembled"
      (t/is (nil? (:deleted-at (session-row session-id)))))

    (t/testing "and no job is created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-import-job-refuses-a-name-that-is-not-an-import-job
  (let [profile (th/create-profile* 1)
        out     (create-import-job! (:id profile) (:default-project-id profile)
                                    :name :export-binfile
                                    :file (upload-file))]

    (t/is (not (th/success? out)))
    (t/is (= :not-a-job-of-the-family (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))))

(t/deftest create-import-job-refuses-a-version-that-does-not-exist
  (let [profile (th/create-profile* 1)
        out     (create-import-job! (:id profile) (:default-project-id profile)
                                    :params {:project-id (:default-project-id profile)
                                             :name       "imported"
                                             :version    2}
                                    :file (upload-file))]

    (t/is (not (th/success? out)))
    (t/is (= :data-validation (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))
    (t/testing "and nothing was staged for it"
      (t/is (zero? (live-storage-objects))))))

(t/deftest create-import-job-releases-the-package-when-the-submit-fails
  (let [profile (th/create-profile* 1)
        out     (with-redefs [jobs/submit (fn [& _]
                                            (ex/raise :type :internal
                                                      :code :boom
                                                      :hint "cannot create the job"))]
                  (create-import-job! (:id profile) (:default-project-id profile)
                                      :file (upload-file)))]

    (t/testing "the failure reaches the caller"
      (t/is (not (th/success? out))))

    (t/testing "the staged package does not stay alive"
      (t/is (zero? (live-storage-objects))))

    (t/testing "and no job row was left behind"
      (t/is (zero? (count-jobs))))))
