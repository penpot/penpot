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
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.jobs :as jobs]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.quotes :as-alias quotes]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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
                       :file-ids       file-ids
                       :export-type    :detach-libraries}
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
      (t/is (= :not-an-export-job (th/ex-code (:error out)))))

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
