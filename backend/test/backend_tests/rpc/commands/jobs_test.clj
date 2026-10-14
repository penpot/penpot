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
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.jobs :as cmd-jobs]
   [app.rpc.quotes :as-alias quotes]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]])
  (:import
   java.util.zip.ZipEntry
   java.util.zip.ZipFile
   java.util.zip.ZipOutputStream))

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

(defn- create-export-binfile-job
  [profile-id file-ids & {:as extra}]
  (th/command! (merge {::th/type       :create-export-binfile-job
                       ::rpc/profile-id profile-id
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

(t/deftest create-export-binfile-job-answers-with-a-pending-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        before  (count-storage-objects)
        out     (create-export-binfile-job (:id profile) #{file-id})]

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
        ;; no runner is running here, so a call that waited for one would
        ;; hang instead of answering: seeing the job pending and no
        ;; artifact produced is what proves the call does not wait
        (t/is (= before (count-storage-objects)))))))

(t/deftest create-export-binfile-job-requires-the-export-type
  ;; the type is part of the contract of the job: a caller that does not
  ;; say how to handle the libraries gets an error, not a package it did
  ;; not ask for
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (th/command! {::th/type       :create-export-binfile-job
                              ::rpc/profile-id (:id profile)
                              :params         {:file-ids #{file-id}}})]

    (t/is (not (th/success? out)))
    (t/is (= :params-validation (th/ex-code (:error out))))
    (t/testing "and nothing was created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-binfile-job-requires-at-least-one-file
  (let [profile (th/create-profile* 1)
        out     (create-export-binfile-job (:id profile) #{})]

    (t/is (not (th/success? out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :no-files-to-export (th/ex-code (:error out))))
    (t/testing "and nothing was created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-binfile-job-checks-the-read-permission
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        file-id (first (:file-ids (import-fixture! owner)))
        out     (create-export-binfile-job (:id other) #{file-id})]

    (t/testing "a file the caller cannot read is not exported"
      (t/is (not (th/success? out)))
      (t/is (= :not-found (th/ex-type (:error out)))))

    (t/testing "and no job was created for it"
      (t/is (zero? (count-jobs))))))

(t/deftest create-export-binfile-job-enforces-the-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-export-jobs-per-profile 1})}]

    (let [profile (th/create-profile* 1)
          file-id (first (:file-ids (import-fixture! profile)))]

      (t/testing "the first job fits in the quote"
        (t/is (th/success? (create-export-binfile-job (:id profile) #{file-id})))
        (t/is (= 1 (count-jobs))))

      (t/testing "the one that would go over it is refused"
        (let [out (create-export-binfile-job (:id profile) #{file-id})]
          (t/is (not (th/success? out)))
          (t/is (= :restriction (th/ex-type (:error out))))
          (t/is (= :max-quote-reached (th/ex-code (:error out))))
          (t/is (= "export-jobs-per-profile" (:target (ex-data (:error out)))))))

      (t/testing "so the quote is what stopped the second job"
        (t/is (= 1 (count-jobs)))))))

(t/deftest create-export-binfile-job-adds-the-job-id-to-the-audit-props
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (create-export-binfile-job (:id profile) #{file-id})
        props   (::audit/props (meta (:result out)))]

    (t/testing "the audit of the call points at the job and its team"
      (t/is (= (:id (:result out)) (:job-id props)))
      (t/is (= 1 (:files props)))
      (t/is (= "detach-libraries" (:export-type props)))
      (t/is (= (:default-team-id profile) (:team-id props)))
      (t/is (= #{:job-id :files :export-type :team-id} (set (keys props))))
      (t/is (not (contains? (:result out) :team-id))))))

(t/deftest create-export-binfile-job-records-the-team-when-files-share-one
  (let [profile (th/create-profile* 1)
        file-a  (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        file-b  (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        out     (create-export-binfile-job (:id profile)
                                           #{(:id file-a) (:id file-b)})
        props   (::audit/props (meta (:result out)))]
    (t/is (th/success? out))
    (t/is (= (:default-team-id profile) (:team-id props)))
    (t/is (= 2 (:files props)))
    (t/is (not (contains? (:result out) :team-id)))))

(t/deftest create-export-binfile-job-omits-the-team-when-files-span-teams
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        project (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team)})
        file-a  (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        file-b  (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:id project)})
        out     (create-export-binfile-job (:id profile)
                                           #{(:id file-a) (:id file-b)})
        props   (::audit/props (meta (:result out)))]
    (t/is (th/success? out))
    (t/is (nil? (:team-id props)))
    (t/is (= #{:job-id :files :export-type} (set (keys props))))
    (t/is (not (contains? (:result out) :team-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tempfile-with
  "A temporary file holding `content`, as the http layer would leave one
  for a plain upload."
  [content]
  (let [path (fs/create-tempfile :dir tmp/default-tmp-dir
                                 :prefix "test-upload-")]
    (with-open [out (io/output-stream path)]
      (io/write* out (.getBytes ^String content "UTF-8")))
    path))

(defn- upload-chunk
  "One chunk of the fixture, as the upload command takes it. `content`
  replaces the fixture with something that is no package at all."
  [& {:keys [content]}]
  (if (some? content)
    {:filename "package.penpot"
     :path     (tempfile-with content)
     :mtype    "application/zip"
     :size     (count content)}
    (let [source (-> fixture io/resource jio/file)]
      {:filename "package.penpot"
       :path     (th/tempfile fixture)
       :mtype    "application/zip"
       :size     (.length source)})))
(defn- upload-chunked!
  "Upload one chunk and return the session id. By default the chunk is the
  fixture; `:path` uploads another file and `:content` uploads a string
  that is no package at all."
  [profile & {:keys [content path]}]
  (let [session-id (-> (th/command! {::th/type       :create-upload-session
                                     ::rpc/profile-id (:id profile)
                                     :total-chunks  1})
                       :result :session-id)
        out        (th/command! {::th/type        :upload-chunk
                                 ::rpc/profile-id (:id profile)
                                 :session-id    session-id
                                 :index         0
                                 :content       (if (some? path)
                                                  (let [size (.length (jio/file path))]
                                                    {:filename "package.penpot"
                                                     :path     path
                                                     :mtype    "application/zip"
                                                     :size     size})
                                                  (upload-chunk :content content))})]
    (t/is (nil? (:error out)))
    session-id))

(defn- create-import-binfile-job
  [profile-id project-id & {:keys [upload-id] :as extra}]
  (th/command! (merge {::th/type       :create-import-binfile-job
                       ::rpc/profile-id profile-id
                       :params         {:project-id project-id
                                        :name       "imported"}
                       :upload-id      upload-id}
                      extra)))

(defn- session-row
  [session-id]
  (th/db-get :upload-session {:id session-id} {::db/remove-deleted false}))

(defn- package-with-refer
  "The fixture with the key of its manifest renamed to the old spelling,
  `refer`, and keeping the value it already had."
  []
  (let [source (-> fixture io/resource jio/file)
        target (fs/create-tempfile :dir tmp/default-tmp-dir
                                   :prefix "test-refer-")]
    (with-open [zip (ZipFile. source)
                out (ZipOutputStream. (io/output-stream target))]
      (doseq [entry (enumeration-seq (.entries zip))]
        (.putNextEntry out (ZipEntry. (.getName entry)))
        (when-not (.isDirectory entry)
          (let [content (slurp (.getInputStream zip entry))
                content (if (= "manifest.json" (.getName entry))
                          (str/replace content #"\"referer\"(?=\s*:)" "\"refer\"")
                          content)]
            (.write out (.getBytes content "UTF-8"))))
        (.closeEntry out)))
    target))

(defn- manifest-of
  "The manifest of a package, as the reader of the import sees it."
  [path]
  (v3/get-manifest th/*system* path))

(defn- live-storage-objects
  []
  (count (th/db-exec! ["SELECT id FROM storage_object WHERE deleted_at IS NULL"])))

(defn- session-row
  [session-id]
  (th/db-get :upload-session {:id session-id} {::db/remove-deleted false}))

(defn- package-with-refer
  "The fixture with the key of its manifest renamed to the old spelling,
  `refer`, and keeping the value it already had."
  []
  (let [source (-> fixture io/resource jio/file)
        target (fs/create-tempfile :dir tmp/default-tmp-dir
                                   :prefix "test-refer-")]
    (with-open [zip (ZipFile. source)
                out (ZipOutputStream. (io/output-stream target))]
      (doseq [entry (enumeration-seq (.entries zip))]
        (.putNextEntry out (ZipEntry. (.getName entry)))
        (when-not (.isDirectory entry)
          (let [content (slurp (.getInputStream zip entry))
                content (if (= "manifest.json" (.getName entry))
                          (str/replace content #"\"referer\"(?=\s*:)" "\"refer\"")
                          content)]
            (.write out (.getBytes content "UTF-8"))))
        (.closeEntry out)))
    target))

(defn- manifest-of
  "The manifest of a package, as the reader of the import sees it."
  [path]
  (v3/get-manifest th/*system* path))

(defn- assembled-tempfiles
  "The temporary files the chunk assembly leaves behind. The copies this
  test uploads are its own business."
  []
  (->> (.list (jio/file tmp/default-tmp-dir))
       (filter #(str/starts-with? % "penpot.chunked-upload."))
       (seq)))

(t/deftest create-import-binfile-job-audits-the-old-spelling-of-the-manifest-tool
  (let [profile    (th/create-profile* 1)
        package    (package-with-refer)
        expected   (:referer (manifest-of (-> fixture io/resource jio/file)))
        session-id (upload-chunked! profile :path package)
        out        (create-import-binfile-job (:id profile) (:default-project-id profile)
                                              :upload-id session-id)
        props      (::audit/props (meta (:result out)))]

    (t/testing "the package names its tool with the old spelling only"
      (t/is (some? expected))
      (t/is (nil? (:referer (manifest-of package))))
      (t/is (= expected (:refer (manifest-of package)))))

    (t/testing "and the audit carries that value anyway"
      (t/is (th/success? out))
      (t/is (= expected (:referer props))))))

(t/deftest create-import-binfile-job-limits-its-own-concurrency
  ;; the command assembles and stores the package inside the request, so
  ;; it declares the same kind of limit the legacy import command has
  (let [[mdata _] (get (::rpc/methods th/*system*) :create-import-binfile-job)]
    (t/is (= [[:create-import-binfile-job/by-profile ::rpc/profile-id]
              [:create-import-binfile-job/global]]
             (::climit/id mdata)))))

(t/deftest create-export-binfile-job-accepts-the-params-of-a-json-client
  ;; a JSON body carries text and lists, not uuids or sets: the command
  ;; has to read them with the decoder of the job-def, like the runner
  ;; does when it reads a job from its row
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (th/command! {::th/type       :create-export-binfile-job
                              ::rpc/profile-id (:id profile)
                              :params         {:file-ids    [(str file-id)]
                                               :export-type "detach-libraries"}})]

    (t/is (th/success? out))

    (let [job-def (jobs/get-job-def (::jobs/defs th/*system*) :export-binfile)
          params  (jobs/decode-params job-def
                                      (:params (jobs/get-job th/*system*
                                                             (:id (:result out)))))]
      (t/is (= #{file-id} (:file-ids params)))
      (t/is (= :detach-libraries (:export-type params))))))

(t/deftest create-import-binfile-job-accepts-the-params-of-a-json-client
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        out        (th/command! {::th/type       :create-import-binfile-job
                                 ::rpc/profile-id (:id profile)
                                 :params         {:project-id (str (:default-project-id profile))
                                                  :name       "imported"}
                                 :upload-id      session-id})]

    (t/is (th/success? out))))

(t/deftest create-import-binfile-job-never-takes-a-path-from-the-caller
  (let [profile (th/create-profile* 1)
        out     (create-import-binfile-job (:id profile) (:default-project-id profile)
                                           :file {:filename "package.penpot"
                                                  :path     (jio/file "/etc/passwd")
                                                  :mtype    "application/zip"
                                                  :size     0})]

    (t/testing "a plain upload is not a way to name a package"
      (t/is (not (th/success? out)))
      (t/is (= :params-validation (th/ex-code (:error out)))))

    (t/testing "and no job is created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-import-binfile-job-answers-with-a-pending-job
  (let [profile (th/create-profile* 1)
        out     (create-import-binfile-job (:id profile) (:default-project-id profile)
                                           :upload-id (upload-chunked! profile))]

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

(t/deftest create-import-binfile-job-assembles-the-chunks-of-its-upload
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        out        (create-import-binfile-job (:id profile) (:default-project-id profile)
                                              :upload-id session-id)]

    (t/is (th/success? out))

    (t/testing "the assembled package is the resource of the job"
      (let [job (jobs/get-job th/*system* (:id (:result out)))]
        (t/is (some? (:resource-id job)))))

    (t/testing "the upload session is consumed"
      (t/is (some? (:deleted-at (session-row session-id)))))

    (t/testing "and the temporary file of the assembly is gone"
      (t/is (empty? (assembled-tempfiles))))))

(t/deftest create-import-binfile-job-rejects-an-incomplete-upload
  (let [profile    (th/create-profile* 1)
        session-id (-> (th/command! {::th/type       :create-upload-session
                                     ::rpc/profile-id (:id profile)
                                     :total-chunks  2})
                       :result :session-id)
        out        (create-import-binfile-job (:id profile) (:default-project-id profile)
                                              :upload-id session-id)]

    (t/is (not (th/success? out)))
    (t/is (= :missing-chunks (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))))

(t/deftest create-import-binfile-job-refuses-an-upload-of-another-profile
  (let [owner      (th/create-profile* 1)
        other      (th/create-profile* 2)
        session-id (upload-chunked! owner)
        out        (create-import-binfile-job (:id other) (:default-project-id other)
                                              :upload-id session-id)]

    (t/testing "the upload belongs to the profile that made it"
      (t/is (not (th/success? out)))
      (t/is (= :not-found (th/ex-type (:error out)))))

    (t/testing "and it is not consumed by the attempt"
      (t/is (nil? (:deleted-at (session-row session-id)))))

    (t/testing "nor is any job created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-import-binfile-job-checks-the-edition-permission
  (let [owner      (th/create-profile* 1)
        other      (th/create-profile* 2)
        session-id (upload-chunked! other)
        out        (create-import-binfile-job (:id other) (:default-project-id owner)
                                              :upload-id session-id)]

    (t/testing "a job that cannot edit the destination is refused"
      (t/is (not (th/success? out))))

    (t/testing "before the upload is assembled"
      (t/is (nil? (:deleted-at (session-row session-id)))))

    (t/testing "and no job is created"
      (t/is (zero? (count-jobs))))))

(t/deftest create-import-binfile-job-refuses-a-version-that-does-not-exist
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        ;; the chunks of the upload are alive: the GC reclaims them later
        before     (live-storage-objects)
        out        (create-import-binfile-job (:id profile) (:default-project-id profile)
                                              :params {:project-id (:default-project-id profile)
                                                       :name       "imported"
                                                       :version    2}
                                              :upload-id session-id)]

    (t/is (not (th/success? out)))
    (t/is (= :params-validation (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))

    (t/testing "no package was staged for it"
      (t/is (= before (live-storage-objects))))

    (t/testing "and the upload is not consumed by the rejection"
      (t/is (nil? (:deleted-at (session-row session-id)))))

    (t/testing "so nothing had to be assembled either"
      (t/is (empty? (assembled-tempfiles))))))

(t/deftest create-import-binfile-job-rejects-malformed-params-before-assembling
  ;; the shape of the caller params is checked before the chunks are
  ;; assembled: a name over the limit never copies the package to disk
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        before     (live-storage-objects)
        out        (with-redefs [cmd-jobs/assemble-upload
                                 (fn [& _]
                                   (throw (ex-info "assembled" {})))]
                     (create-import-binfile-job (:id profile) (:default-project-id profile)
                                                :params {:project-id (:default-project-id profile)
                                                         :name       (apply str (repeat 251 "x"))}
                                                :upload-id session-id))]

    (t/is (not (th/success? out)))
    (t/is (= :params-validation (th/ex-code (:error out))))
    (t/is (zero? (count-jobs)))

    (t/testing "no package was staged for it"
      (t/is (= before (live-storage-objects))))

    (t/testing "so nothing had to be assembled either"
      (t/is (empty? (assembled-tempfiles))))))

(t/deftest create-import-binfile-job-leaves-no-temporary-when-the-package-is-corrupt
  (let [profile    (th/create-profile* 1)
        ;; a header that says "version 3" (the zip magic) over bytes that
        ;; are not a zip at all: what fails is reading the manifest, once
        ;; the chunks have already been assembled
        session-id (upload-chunked! profile
                                    {:content (str "PK\u0003\u0004" "not a zip")})
        before     (live-storage-objects)
        out        (create-import-binfile-job (:id profile) (:default-project-id profile)
                                              :upload-id session-id)]

    (t/is (not (th/success? out)))
    (t/is (zero? (count-jobs)))

    (t/testing "no package was staged"
      (t/is (= before (live-storage-objects))))

    (t/testing "and the file assembled from the chunks is gone"
      (t/is (empty? (assembled-tempfiles))))))

(t/deftest create-import-binfile-job-releases-the-package-when-the-submit-fails
  (let [profile    (th/create-profile* 1)
        session-id (upload-chunked! profile)
        ;; the chunks of the upload are alive: the GC reclaims them later
        before     (live-storage-objects)
        out        (with-redefs [jobs/submit (fn [& _]
                                               (ex/raise :type :internal
                                                         :code :boom
                                                         :hint "cannot create the job"))]
                     (create-import-binfile-job (:id profile) (:default-project-id profile)
                                                :upload-id session-id))]

    (t/testing "the failure reaches the caller"
      (t/is (not (th/success? out))))

    (t/testing "the staged package does not stay alive"
      (t/is (= before (live-storage-objects))))

    (t/testing "and no job row was left behind"
      (t/is (zero? (count-jobs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; READ
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-job!
  [profile-id job-id]
  (th/command! {::th/type       :get-job
                ::rpc/profile-id profile-id
                :id             job-id}))

(defn- claim-job!
  "Drive a pending job to `running`, as the dispatcher would: the terminal
  writers only accept a job that is being executed."
  [job-id]
  (jobs/claim th/*system* job-id
              (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at))))

(t/deftest get-job-answers-with-the-state-of-the-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (:id (:result (create-export-binfile-job (:id profile) #{file-id})))
        out     (get-job! (:id profile) job-id)]

    (t/is (th/success? out))
    (let [result (:result out)]
      (t/is (= job-id (:id result)))
      (t/is (= "pending" (:status result)))
      (t/is (= "export-binfile" (:name result)))
      (t/is (ct/inst? (:created-at result)))
      (t/is (ct/inst? (:expires-at result)))

      (t/testing "a job that has not finished has nothing to report yet"
        (t/is (not (contains? result :result)))
        (t/is (not (contains? result :error)))))))

(t/deftest get-job-answers-with-the-result-of-a-finished-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (:id (:result (create-export-binfile-job (:id profile) #{file-id})))]

    (t/is (pos? (claim-job! job-id)))
    (t/is (pos? (jobs/complete th/*system* :job-id job-id
                               :result {:value 42 :file-ids [file-id]})))

    (let [result (:result (get-job! (:id profile) job-id))]
      (t/is (= "completed" (:status result)))
      (t/testing "the result comes back as the map the handler produced"
        ;; the row holds plain json, so a uuid comes back as the text it is
        (t/is (= {:value 42 :file-ids [(str file-id)]} (:result result))))
      (t/is (not (contains? result :error))))))

(t/deftest get-job-answers-with-the-error-of-a-failed-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (:id (:result (create-export-binfile-job (:id profile) #{file-id})))]

    (t/is (pos? (claim-job! job-id)))
    (t/is (pos? (jobs/fail th/*system* job-id {:type :internal
                                               :code :boom
                                               :hint "boom"})))

    (let [result (:result (get-job! (:id profile) job-id))]
      (t/is (= "failed" (:status result)))
      (t/testing "the error is the public summary, with its keywords back"
        (t/is (= {:type :internal :code :boom :hint "boom"} (:error result))))
      (t/testing "and it carries no result"
        (t/is (not (contains? result :result)))))))

(t/deftest get-job-hides-a-job-of-another-profile
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        file-id (first (:file-ids (import-fixture! owner)))
        job-id  (:id (:result (create-export-binfile-job (:id owner) #{file-id})))]

    (t/testing "the owner reads it"
      (t/is (th/success? (get-job! (:id owner) job-id))))

    (t/testing "another profile gets the answer of a job that does not exist"
      (let [out (get-job! (:id other) job-id)]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "and so does an unknown id"
      (let [out (get-job! (:id owner) (uuid/next))]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CANCEL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cancel-job
  [profile-id job-id]
  (th/command! {::th/type       :cancel-job
                ::rpc/profile-id profile-id
                :id             job-id}))

(t/deftest cancel-job-cancels-a-running-job
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (:id (:result (create-export-binfile-job (:id profile) #{file-id})))]

    (t/is (pos? (claim-job! job-id)))

    (let [out (cancel-job (:id profile) job-id)]
      (t/is (th/success? out))
      (t/testing "the answer names the cancelled job"
        (t/is (= job-id (:id (:result out))))
        (t/is (= "cancelled" (:status (:result out)))))
      (t/testing "and the row is cancelled"
        (t/is (= "cancelled" (:status (jobs/get-job th/*system* job-id))))))))

(t/deftest cancel-job-hides-a-job-of-another-profile
  (let [owner   (th/create-profile* 1)
        other   (th/create-profile* 2)
        file-id (first (:file-ids (import-fixture! owner)))
        job-id  (:id (:result (create-export-binfile-job (:id owner) #{file-id})))]

    (t/testing "another profile gets the answer of a job that does not exist"
      (let [out (cancel-job (:id other) job-id)]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "and the job keeps running untouched"
      (t/is (pos? (claim-job! job-id)))
      (t/is (= "running" (:status (jobs/get-job th/*system* job-id)))))))

(t/deftest cancel-job-answers-a-finished-job-without-error
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (:id (:result (create-export-binfile-job (:id profile) #{file-id})))]

    (t/is (pos? (claim-job! job-id)))
    (t/is (pos? (jobs/complete th/*system* :job-id job-id
                               :result {:value 42})))

    (t/testing "cancelling what is already over answers its state"
      (let [out (cancel-job (:id profile) job-id)]
        (t/is (th/success? out))
        (t/is (= "completed" (:status (:result out))))))))

(t/deftest cancel-job-rejects-a-malformed-id
  (let [profile (th/create-profile* 1)
        out     (cancel-job (:id profile) "nope")]
    (t/is (not (th/success? out)))
    (t/is (= :validation (th/ex-type (:error out))))))
