;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.binfile-jobs-integration-test
  "The whole chain of a binfile job, without a full end-to-end: the
  command creates the job, the dispatcher sends it to its own queue, the
  runner of that queue executes it and the artifact it produced (or
  consumed) ends up where the ledger says."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.jobs :as bfj]
   [app.binfile.v3 :as v3]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [app.msgbus :as-alias mbus]
   [app.redis :as rds]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.worker :as wrk]
   [app.worker.dispatcher :as wdisp]
   [app.worker.runner :as wrkr]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(def ^:private fixture
  "A real penpot export, the same fixture the binfile tests read."
  "backend_tests/test_files/svg-attrs-camel-case.penpot")

(def ^:private fast-timeout
  "A runner of an empty queue must not make the test wait for the default
  timeout of the production loop."
  (ct/duration {:millis 200}))

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

(defn- upload-chunked!
  "Upload the fixture as a single chunk and return the session id."
  [profile]
  (let [source     (-> fixture io/resource jio/file)
        session-id (-> (th/command! {::th/type       :create-upload-session
                                     ::rpc/profile-id (:id profile)
                                     :total-chunks  1})
                       :result :session-id)
        out        (th/command! {::th/type        :upload-chunk
                                 ::rpc/profile-id (:id profile)
                                 :session-id    session-id
                                 :index         0
                                 :content       {:filename "package.penpot"
                                                 :path     (th/tempfile fixture)
                                                 :mtype    "application/zip"
                                                 :size     (.length source)}})]
    (t/is (nil? (:error out)))
    session-id))

(defn- dispatcher-cfg
  []
  {::db/pool     th/*pool*
   ::rds/client  (get th/*system* :app.redis/client)
   ::mtx/metrics (get th/*system* :app.metrics/metrics)
   ::wrk/tenant  (cf/get :tenant)
   ::wdisp/lease (cf/get-jobs-lease)
   ::wdisp/timeout (ct/duration "10s")})

(defn- runner-cfg
  "The cfg the runner loop needs, with the real job-defs of the system:
  the handler closes over the component of its own job-def."
  [queue]
  {::db/pool      th/*pool*
   ::rds/conn     (rds/connect {::rds/client  (get th/*system* :app.redis/client)
                                ::mtx/metrics (get th/*system* :app.metrics/metrics)})
   ::jobs/defs    (::jobs/defs th/*system*)
   ::mtx/metrics  (get th/*system* :app.metrics/metrics)
   ::mbus/msgbus  (get th/*system* :app.msgbus/msgbus)
   ::wrkr/id      "test-runner"
   ::wrkr/queue   queue
   ::wrk/tenant   (cf/get :tenant)
   ::wrkr/timeout fast-timeout})

(defn- run-one
  [cfg]
  (@#'wrkr/run-worker-loop cfg))

(defn- job-row
  [job-id]
  (-> (th/db-get :job {:id job-id})
      (update :result #(cond-> % (db/pgobject? %) db/decode-json-pgobject))
      (update :error jobs/decode-job-error)))

(defn- project-files
  [project-id]
  (th/db-exec! ["SELECT id FROM file WHERE project_id = ? AND deleted_at IS NULL"
                project-id]))

(defn- download
  "The artifact as a local file, as a client would fetch it."
  [resource-id]
  (let [object (sto/get-object (get-storage) resource-id)
        path   (tmp/tempfile* :suffix ".penpot")]
    (with-open [out (io/output-stream path)]
      (io/copy (sto/get-object-data (get-storage) object) out))
    path))

(defn- create-export-binfile-job
  [profile file-ids]
  (th/command! {::th/type       :create-export-binfile-job
                ::rpc/profile-id (:id profile)
                :params         {:file-ids    file-ids
                                 :export-type :detach-libraries}}))

(defn- create-import-binfile-job
  [profile project-id]
  (th/command! {::th/type       :create-import-binfile-job
                ::rpc/profile-id (:id profile)
                :params         {:project-id project-id
                                 :name       "imported"}
                :upload-id      (upload-chunked! profile)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-export-job-runs-to-completion-in-its-own-queue
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        out     (create-export-binfile-job profile #{file-id})
        job-id  (:id (:result out))]

    (t/is (th/success? out))

    (wdisp/run-batch (dispatcher-cfg))
    (t/testing "the dispatcher schedules it, and it waits for its queue"
      (t/is (= "scheduled" (:status (jobs/get-job th/*system* job-id)))))

    (t/testing "a runner of another queue does not take it"
      (run-one (runner-cfg "default"))
      (t/is (= "scheduled" (:status (jobs/get-job th/*system* job-id)))))

    (t/testing "the runner of its queue does"
      (run-one (runner-cfg "binfile"))
      (t/is (= "completed" (:status (jobs/get-job th/*system* job-id)))))

    (let [row (job-row job-id)
          job (:result row)]
      (t/testing "the runner associated the artifact of the completion envelope"
        (t/is (some? (:resource-id row))))

      (t/testing "the result of the job is the descriptor the handler built"
        (t/is (= "export.penpot" (:filename job)))
        (t/is (= "application/zip" (:mtype job)))
        (t/is (pos? (:size job)))
        (t/is (some? (:resource-uri job))))

      (t/testing "and the artifact is the package of those files"
        (t/is (= [file-id]
                 (mapv :id (:files (v3/get-manifest th/*system*
                                                    (download (:resource-id row))))))))

      (t/testing "the artifact belongs to the profile of the job"
        (let [object (sto/get-object (get-storage) (:resource-id row))]
          (t/is (= sto/job-resource-bucket (:bucket (meta object))))
          (t/is (= (:id profile) (:profile-id (meta object))))))

      (t/testing "the runner reached the terminal state through the row"
        (t/is (= "completed" (:status row)))
        (t/is (some? (:completed-at row)))))))

(t/deftest the-import-job-runs-to-completion-in-its-own-queue
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        out        (create-import-binfile-job profile project-id)
        job-id     (:id (:result out))
        ;; read before the run: the handler drops the reference as soon as
        ;; it releases the package
        input-id   (:resource-id (th/db-get :job {:id job-id}))]

    (t/is (th/success? out))

    (wdisp/run-batch (dispatcher-cfg))
    (run-one (runner-cfg "binfile"))

    (let [row (job-row job-id)
          job (:result row)]
      (t/testing "the job ends completed"
        (t/is (= "completed" (:status row))))

      (t/testing "the files of the package are in the destination"
        ;; the result is JSON: its ids come back as text, the rows as uuids
        (t/is (= (set (map str (:file-ids job)))
                 (set (map (comp str :id) (project-files project-id))))))

      (t/testing "the result carries the files and their resolution"
        (t/is (pos? (count (:file-ids job))))
        (t/is (= "imported" (:name job))))

      (t/testing "the package it consumed is released"
        (t/is (some? (:deleted-at (th/db-get :storage-object
                                             {:id input-id}
                                             {::db/remove-deleted false})))))

      (t/testing "and the job no longer points at it"
        (t/is (nil? (:resource-id row)))))))

(t/deftest the-adapter-reports-progress-through-heartbeat
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (uuid/next)
        calls   (atom [])]

    (th/db-insert! :job {:id           job-id
                         :name         "export-binfile"
                         :tenant       (cf/get :tenant)
                         :queue        "binfile"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  0
                         :retry-num    0
                         :status       "running"
                         :profile-id   (:id profile)
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})

    (with-redefs [jobs/heartbeat (fn [_cfg & {:as options}]
                                   (swap! calls conj options)
                                   1)]
      (bfj/export-files th/*system*
                        (jobs/make-context (jobs/get-job th/*system* job-id))
                        {:ids         #{file-id}
                         :export-type :detach-libraries
                         :output      (tmp/tempfile* :suffix ".penpot")}))

    (let [reports (map :progress @calls)]

      (t/testing "the adapter reports progress through heartbeat"
        (t/is (pos? (count reports))))

      (t/testing "with the payload of the job contract, and nothing else"
        (t/is (every? keyword? (map :stage reports)))
        (t/is (every? #(<= (count (keys %)) 2) reports)))

      (t/testing "about the job of the context, not one the caller named"
        (t/is (every? #(= job-id (:job-id %)) @calls))))))
