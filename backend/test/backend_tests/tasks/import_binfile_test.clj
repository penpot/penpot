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
   [app.binfile.jobs :as bfj]
   [app.binfile.v1 :as v1]
   [app.binfile.v3 :as v3]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.loggers.audit :as audit]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.import-binfile :as import-binfile]
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
                          (assoc ::bfc/file-ids #{file-id})
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

(def ^:private real-audit-submit audit/submit)

(defn- get-audit-events
  [event-name]
  (->> (th/db-exec! ["SELECT * FROM audit_log WHERE name = ? AND source = 'backend'"
                     event-name])
       (mapv #(update % :props db/decode-transit-pgobject))))

(defn- create-import-job
  [profile params resource-id]
  (let [job-id (th/create-job* :import-binfile {:profile-id (:id profile)
                                                :resource-id resource-id})]
    (th/db-update! :job {:params (db/json params)} {:id job-id})
    job-id))

(defn- project-modified-at
  [project-id]
  (:modified-at (th/db-get :project {:id project-id})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-import-handler-imports-the-package-of-its-job
  (let [profile  (th/create-profile* 1)
        staged   (stage-package! profile (fixture-path))
        job-id   (th/create-job* :import-binfile {:profile-id (:id profile)
                                                  :resource-id (:resource-id staged)})
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
        job-id     (th/create-job* :import-binfile {:profile-id (:id profile)
                                                    :resource-id (:resource-id staged)})
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
        job-id   (th/create-job* :import-binfile {:profile-id (:id profile)
                                                  :resource-id (:resource-id staged)})
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
        job-id  (th/create-job* :import-binfile {:profile-id (:id other)
                                                 :resource-id (:resource-id staged)})
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
        job-id  (th/create-job* :import-binfile {:profile-id (:id profile)
                                                 :resource-id (:resource-id staged)})
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

(t/deftest an-import-refuses-a-project-that-changed-teams
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        staged     (stage-package! profile (fixture-path))
        params     {:project-id project-id :team-id (uuid/next)
                    :name "imported" :version 3}
        job-id     (create-import-job profile params (:resource-id staged))
        before     (project-files project-id)]
    (t/is (thrown? Throwable (run-import job-id params)))
    (t/is (= before (project-files project-id)))))

(t/deftest an-import-cancelled-while-it-runs-leaves-nothing-behind
  (let [profile (th/create-profile* 1)
        staged  (stage-package! profile (fixture-path))
        job-id  (th/create-job* :import-binfile {:profile-id (:id profile)
                                                 :resource-id (:resource-id staged)})
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
        job-id  (th/create-job* :import-binfile {:profile-id (:id profile)
                                                 :resource-id (:resource-id staged)})
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

(t/deftest a-result-that-does-not-conform-leaves-nothing-behind
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        staged     (stage-package! profile (fixture-path))
        job-id     (th/create-job* :import-binfile {:profile-id (:id profile)
                                                    :resource-id (:resource-id staged)})
        before     (project-files project-id)]

    ;; the core does its work inside the transaction and answers with a result
    ;; the schema of the task does not accept: the check runs before the
    ;; transaction commits, so the work is rolled back with it
    (with-redefs [v3/import-files!
                  (fn [cfg]
                    (th/create-file* cfg 7 {:profile-id (:id profile)
                                            :project-id project-id
                                            :is-shared  false
                                            :name       "half imported"})
                    {:file-ids   [(uuid/next)]
                     :resolution "not-a-map"})]

      (t/testing "the handler refuses the result"
        (t/is (thrown-with-msg? Exception #"invalid result of an import job"
                                (run-import job-id {:project-id project-id
                                                    :name       "imported"
                                                    :version    3}))))

      (t/testing "and the import is rolled back: the destination keeps no files"
        (t/is (= before (project-files project-id)))))))

(t/deftest import-audit-excludes-unlisted-job-params
  (let [profile (th/create-profile* 1)
        event   (atom nil)
        job     {:id (uuid/next)
                 :profile-id (:id profile)
                 :status "cancelled"
                 :params {:project-id (:default-project-id profile)
                          :name "imported"
                          :version 3
                          :future-param "not for audit"}}]
    (with-redefs [audit/submit (fn [_ submitted] (reset! event submitted))]
      (#'import-binfile/submit-import-event th/*system* job))
    (t/is (= {:project-id (:default-project-id profile)
              :name "imported"
              :version 3
              :job-id (:id job)
              :status "cancelled"
              :team-id (:default-team-id profile)}
             (:props @event)))))

(t/deftest a-completed-import-submits-its-audit-events
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile)
                   :name "imported" :version 3
                   :referer "penpot" :generated-by "penpot/2.20.0"}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))
          result  (run-import job-id params)
          file-id (first (:file-ids result))]
      (t/is (empty? (get-audit-events "import-binfile")))
      (t/is (= 1 (jobs/complete th/*system* :job-id job-id :result result)))
      (t/is (zero? (jobs/complete th/*system* :job-id job-id :result result)))
      (let [[event :as events] (get-audit-events "import-binfile")
            [created :as created-events] (get-audit-events "create-file")]
        (t/is (= 1 (count events)))
        (t/is (= "command" (:type event)))
        (t/is (= (:id profile) (:profile-id event)))
        (t/is (= {:status "completed" :job-id job-id
                  :project-id (:default-project-id profile)
                  :team-id (:default-team-id profile)
                  :name "imported" :version 3 :referer "penpot"
                  :generated-by "penpot/2.20.0"
                  :file-ids [file-id] :file-count 1}
                 (:props event)))
        (t/is (= 1 (count created-events)))
        (t/is (= file-id (-> created :props :id)))
        (t/is (= (:id profile) (:profile-id created)))
        (t/is (= (:default-team-id profile) (-> created :props :team-id)))))))

(t/deftest a-failed-import-submits-its-terminal-outcome
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))]
      (with-redefs [v3/import-files! (fn [_] (throw (ex-info "boom" {:type :internal})))]
        (t/is (thrown? Throwable (run-import job-id params))))
      (t/is (= 1 (jobs/fail th/*system* job-id {:type :internal :code :unexpected-error :hint "import failed"})))
      (t/is (zero? (jobs/fail th/*system* job-id {:type :internal :code :unexpected-error :hint "import failed"})))
      (let [[event :as events] (get-audit-events "import-binfile")]
        (t/is (= 1 (count events)))
        (t/is (= (:id profile) (:profile-id event)))
        (t/is (= "failed" (-> event :props :status)))
        (t/is (= job-id (-> event :props :job-id)))
        (t/is (not (contains? (:props event) :file-ids)))
        (t/is (not (contains? (:props event) :file-count))))
      (t/is (empty? (get-audit-events "create-file"))))))

(t/deftest a-queued-cancellation-submits-one-outcome-without-loading-input
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          job-id  (create-import-job profile params nil)]
      (th/db-update! :job {:status "new"} {:id job-id})
      (t/is (= 1 (jobs/cancel th/*system* job-id)))
      (t/is (zero? (jobs/cancel th/*system* job-id)))
      (t/is (zero? (jobs/complete th/*system* :job-id job-id :result nil)))
      (let [[event :as events] (get-audit-events "import-binfile")]
        (t/is (= 1 (count events)))
        (t/is (= (:id profile) (:profile-id event)))
        (t/is (= "cancelled" (-> event :props :status)))
        (t/is (= job-id (-> event :props :job-id)))
        (t/is (not (contains? (:props event) :file-ids)))
        (t/is (not (contains? (:props event) :file-count))))
      (t/is (empty? (get-audit-events "create-file"))))))

(t/deftest cancellation-after-the-last-check-can-leave-imported-files
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))
          run     bfj/run
          result  (with-redefs [bfj/run (fn [cfg context f]
                                          (let [result (run cfg context f)]
                                            (jobs/cancel th/*system* job-id)
                                            result))]
                    (run-import job-id params))]
      (t/is (= "cancelled" (:status (jobs/get-job th/*system* job-id))))
      (t/is (zero? (jobs/complete th/*system* :job-id job-id :result result)))
      (t/is (= (set (:file-ids result))
               (set (map :id (project-files (:default-project-id profile))))))
      (t/is (= ["cancelled"] (mapv #(get-in % [:props :status])
                                   (get-audit-events "import-binfile"))))
      (t/is (= 1 (count (get-audit-events "create-file")))))))

(t/deftest file-created-events-require-full-audit-persistence
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? false
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))
          result  (run-import job-id params)]
      (t/is (= 1 (count (:file-ids result))))
      (t/is (empty? (get-audit-events "create-file"))))))

(t/deftest a-terminal-audit-failure-does-not-undo-the-committed-import
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))
          result  (run-import job-id params)]
      (with-redefs [audit/submit (fn [cfg event]
                                   (real-audit-submit cfg event)
                                   (throw (ex-info "audit failure" {})))]
        (t/is (thrown-with-msg? Exception #"audit failure"
                                (jobs/complete th/*system* :job-id job-id :result result))))
      (t/is (= "running" (:status (jobs/get-job th/*system* job-id))))
      (t/is (empty? (get-audit-events "import-binfile")))
      (t/is (= (set (:file-ids result))
               (set (map :id (project-files (:default-project-id profile))))))
      (t/is (= 1 (count (get-audit-events "create-file")))))))

(t/deftest a-file-created-audit-failure-leaves-the-imported-file-committed
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))]
      (with-redefs [audit/submit (fn [cfg event]
                                   (real-audit-submit cfg event)
                                   (throw (ex-info "audit failure" {})))]
        (t/is (thrown-with-msg? Exception #"audit failure" (run-import job-id params))))
      (t/is (= 1 (count (project-files (:default-project-id profile)))))
      (t/is (empty? (get-audit-events "create-file")))
      (t/is (empty? (get-audit-events "import-binfile"))))))

(t/deftest an-aborted-import-does-not-report-a-cancellation
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/telemetry-enabled? false
                cf/webhooks-enabled? false]
    (let [profile (th/create-profile* 1)
          params  {:project-id (:default-project-id profile) :name "imported" :version 3}
          staged  (stage-package! profile (fixture-path))
          job-id  (create-import-job profile params (:resource-id staged))]
      (th/db-update! :job {:status "aborted"} {:id job-id})
      (t/is (thrown? Throwable (run-import job-id params)))
      (t/is (zero? (jobs/cancel th/*system* job-id)))
      (t/is (empty? (get-audit-events "import-binfile")))
      (t/is (empty? (get-audit-events "create-file"))))))
