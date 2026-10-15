;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.binfile-jobs-test
  "The binfile work of a job: the shared adapter that runs it with its
  progress reported as job events, and the two job-defs that expose it."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.jobs :as bfj]
   [app.binfile.v3 :as v3]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.rpc.commands.teams :as teams]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.events :as events]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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

(defn- make-job!
  "A job row as the substrate writes it: running, owned by a profile and
  holding a resource when the test needs one."
  [profile-id resource-id]
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
                         :resource-id  resource-id
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    id))

(defn- job-cfg
  "The system cfg as the runner hands it to a handler. The job id is NOT
  bound here: the adapter takes it from the context, because the progress
  bridge runs on a thread of its own."
  []
  th/*system*)

(defn- job-context
  [job-id]
  (jobs/make-context (jobs/get-job th/*system* job-id)))

(defn- progress-events
  "The progress events of a job, oldest first, with decoded payloads."
  [job-id]
  (->> (th/db-exec! ["SELECT payload FROM job_event
                       WHERE job_id = ? AND kind = 'progress'
                       ORDER BY created_at ASC, id ASC"
                     job-id])
       (mapv #(jobs/decode-progress (:payload %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADAPTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest export-through-the-adapter-produces-the-artifact
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile) nil)
        output  (tmp/tempfile* :suffix ".penpot")]

    (bfj/export-files (job-cfg) (job-context job-id)
                      {:ids         #{file-id}
                       :export-type :detach-libraries
                       :output      output})

    (t/testing "the artifact is a penpot file with its manifest"
      (t/is (fs/exists? output))
      (t/is (= [file-id] (mapv :id (:files (v3/get-manifest th/*system* output))))))

    (t/testing "the progress of the core became milestones of the job"
      (let [events (progress-events job-id)]
        (t/is (pos? (count events)))
        (t/is (every? keyword? (map :stage events)))
        (t/testing "the first one is the file in course, with its position"
          (let [event (first events)]
            (t/is (= :files (:stage event)))
            (t/is (= {:current 1 :total 1} (get-in event [:counters :files])))))
        (t/testing "and nothing else: the event is the stage and the counters"
          (t/is (every? #(<= (count (keys %)) 2) events)))))))

(t/deftest import-through-the-adapter-imports-the-package
  (let [profile  (th/create-profile* 1)
        team     (teams/get-team th/*system*
                                 :profile-id (:id profile)
                                 :project-id (:default-project-id profile))
        staged   (js/put-resource th/*system* (:id profile)
                                  {:content  (sto/content (fixture-path))
                                   :filename "package.penpot"
                                   :mtype    "application/zip"})
        job-id   (make-job! (:id profile) (:resource-id staged))
        context  (job-context job-id)
        result   (bfj/import-files (job-cfg) context
                                   {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :team       team
                                    :name       "imported"
                                    :input      (js/load-input th/*system* context)
                                    :version    3})]

    (t/testing "the package is imported in the destination project"
      (t/is (= 1 (count (:file-ids result)))))

    (t/testing "the job reported its progress"
      (t/is (pos? (count (progress-events job-id)))))))

(t/deftest the-adapter-composes-a-milestone-per-tap
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile) nil)
        reports (atom [])]

    ;; the substrate throttles the durable events, so the whole stream of
    ;; milestones is only visible with the writes intercepted
    (with-redefs [jobs/heartbeat (fn [_cfg & {:as options}]
                                   (swap! reports conj (:progress options))
                                   1)]
      (bfj/export-files (job-cfg) (job-context job-id)
                        {:ids         #{file-id}
                         :export-type :detach-libraries
                         :output      (tmp/tempfile* :suffix ".penpot")}))

    (t/testing "the file opens the run with its position"
      (let [event (first @reports)]
        (t/is (= :files (:stage event)))
        (t/is (= {:current 1 :total 1} (get-in event [:counters :files])))))

    (t/testing "the stages inside the file carry it as context, with their own counter"
      (let [event (first (filter #(= :pages (:stage %)) @reports))]
        (t/is (= {:current 1 :total 1} (get-in event [:counters :files])))
        (t/is (pos? (get-in event [:counters :pages :current])))
        (t/is (pos? (get-in event [:counters :pages :total])))))

    (t/testing "a stage outside a file carries no file context"
      (let [events (filter #(= :storage-objects (:stage %)) @reports)]
        (t/is (pos? (count events)))
        (t/is (every? #(nil? (get-in % [:counters :files])) events))))

    (t/testing "and every milestone is a stage and its counters, and nothing else"
      (t/is (every? #(<= (count (keys %)) 2) @reports)))))

(t/deftest a-tap-carries-the-outer-counters-in-course
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile) nil)
        taps    (atom [])]

    ;; the taps themselves, before the adapter turns them into events
    (with-redefs [events/tap (fn [_type data] (swap! taps conj data))]
      (bfj/export-files (job-cfg) (job-context job-id)
                        {:ids         #{file-id}
                         :export-type :detach-libraries
                         :output      (tmp/tempfile* :suffix ".penpot")}))

    (t/testing "the tap that opens the file carries the outer counters"
      (let [tap (first (filter #(= :file (:section %)) @taps))]
        (t/is (= {:files {:current 1 :total 1}} (:outer-counters tap)))))

    (t/testing "every tap inside the file carries them as context"
      (let [inner (filter #(some? (:file-id %)) @taps)]
        (t/is (pos? (count inner)))
        (t/is (every? #(= {:files {:current 1 :total 1}} (:outer-counters %)) inner))))

    (t/testing "a tap outside a file carries no outer counters"
      (let [outside (filter #(= :storage-object (:section %)) @taps)]
        (t/is (pos? (count outside)))
        (t/is (every? #(nil? (:outer-counters %)) outside))))))

(t/deftest a-milestone-merges-outer-counters-with-its-own
  (let [milestone @#'bfj/milestone]
    (t/testing "outer scopes survive next to the counter of the stage"
      (t/is (= {:stage :pages
                :counters {:teams {:current 1 :total 1}
                           :projects {:current 2 :total 2}
                           :files {:current 1 :total 4}
                           :pages {:current 2 :total 5}}}
               (milestone {:section :page
                           :current 2 :total 5
                           :outer-counters {:teams {:current 1 :total 1}
                                            :projects {:current 2 :total 2}
                                            :files {:current 1 :total 4}}}))))
    (t/testing "a file tap needs no own counter, the outer one is enough"
      (t/is (= {:stage :files
                :counters {:files {:current 1 :total 2}}}
               (milestone {:section :file
                           :outer-counters {:files {:current 1 :total 2}}}))))))

(t/deftest entering-a-file-keeps-the-counters-of-the-outer-scopes
  (let [cfg  {::v3/outer-counters {:teams {:current 1 :total 1}}}
        cfg' (@#'v3/with-outer-counter cfg :files {:current 2 :total 4})]
    (t/is (= {:teams {:current 1 :total 1}
              :files {:current 2 :total 4}}
             (::v3/outer-counters cfg')))))

(t/deftest the-import-adapter-composes-the-same-milestones
  (let [profile (th/create-profile* 1)
        team    (teams/get-team th/*system*
                                :profile-id (:id profile)
                                :project-id (:default-project-id profile))
        staged  (js/put-resource th/*system* (:id profile)
                                 {:content  (sto/content (fixture-path))
                                  :filename "package.penpot"
                                  :mtype    "application/zip"})
        job-id  (make-job! (:id profile) (:resource-id staged))
        context (job-context job-id)
        reports (atom [])]

    (with-redefs [jobs/heartbeat (fn [_cfg & {:as options}]
                                   (swap! reports conj (:progress options))
                                   1)]
      (bfj/import-files (job-cfg) context
                        {:profile-id (:id profile)
                         :project-id (:default-project-id profile)
                         :team       team
                         :name       "imported"
                         :input      (js/load-input th/*system* context)
                         :version    3}))

    (t/testing "the run opens with the manifest, which has no units to count"
      (let [event (first @reports)]
        (t/is (= :manifest (:stage event)))
        (t/is (nil? (:counters event)))))

    (t/testing "the file carries its position"
      (let [event (first (filter #(= :files (:stage %)) @reports))]
        (t/is (= {:current 1 :total 1} (get-in event [:counters :files])))))

    (t/testing "a stage inside the file carries it as context, with its own counter"
      (let [event (first (filter #(= :pages (:stage %)) @reports))]
        (t/is (= {:current 1 :total 1} (get-in event [:counters :files])))
        (t/is (pos? (get-in event [:counters :pages :current])))
        (t/is (pos? (get-in event [:counters :pages :total])))))

    (t/testing "and every milestone is a stage and its counters, and nothing else"
      (t/is (every? #(<= (count (keys %)) 2) @reports)))))

(t/deftest a-job-that-is-no-longer-active-stops-the-work
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile) nil)
        context (job-context job-id)]

    (t/testing "a running job is active"
      (t/is (nil? (bfj/check-active th/*system* context))))

    (t/testing "a cancelled job is not"
      (th/db-update! :job {:status "cancelled"} {:id job-id})
      (let [cause (try (bfj/check-active th/*system* context) nil
                       (catch Throwable raised raised))]
        (t/is (= :interrupt (th/ex-type cause)))
        (t/is (= "cancelled" (:status (ex-data cause))))))

    (t/testing "so the export stops at its next progress point"
      (let [output (tmp/tempfile* :suffix ".penpot")
            cause  (try
                     (bfj/export-files (job-cfg) context
                                       {:ids         #{file-id}
                                        :export-type :detach-libraries
                                        :output      output})
                     nil
                     (catch Throwable raised raised))]
        (t/is (= :interrupt (th/ex-type cause)))
        (t/is (zero? (count (progress-events job-id))))))

    (t/testing "a job that already completed is not active either"
      (th/db-update! :job {:status "completed"} {:id job-id})
      (let [cause (try (bfj/check-active th/*system* context) nil
                       (catch Throwable raised raised))]
        (t/is (= :interrupt (th/ex-type cause)))))

    (t/testing "and neither is a job row that is gone"
      (th/db-force-delete :job {:id job-id})
      (let [cause (try (bfj/check-active th/*system* context) nil
                       (catch Throwable raised raised))]
        (t/is (= :interrupt (th/ex-type cause)))
        (t/is (= "gone" (:status (ex-data cause))))))))

(t/deftest an-interrupted-beat-stops-the-core-at-its-tap
  (let [steps   (atom [])
        context {:id (uuid/next)}
        cause   (ex-info "the job is no longer active"
                         {:type :interrupt :code :job-interrupted})]
    (with-redefs [jobs/heartbeat (fn [& _] (throw cause))]
      (let [raised (try (@#'bfj/with-progress th/*system* context
                                              (fn []
                                                (swap! steps conj :before)
                                                ;; the sink calls the beat
                                                ;; inline, so its interrupt
                                                ;; aborts the run here
                                                (events/tap :progress {:section :file})
                                                (swap! steps conj :after)))
                        nil
                        (catch Throwable stopped stopped))]
        (t/is (= :interrupt (:type (ex-data raised))))
        (t/is (= :job-interrupted (:code (ex-data raised))))
        (t/is (= [:before] @steps)
              "the core stopped at its next tap, the step after never ran")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB-DEFS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-binfile-jobs-follow-the-substrate-contract
  (let [defs (::jobs/defs th/*system*)]

    (t/testing "the export job produces the resource of a user job"
      (let [def (jobs/get-job-def defs :export-binfile)]
        (t/is (= :export (::jobs/family def)))
        (t/is (= :output (::jobs/resource-role def)))
        (t/is (fn? (::jobs/handler def)))))

    (t/testing "the import job consumes the resource of the job"
      (let [def (jobs/get-job-def defs :import-binfile)]
        (t/is (= :import (::jobs/family def)))
        (t/is (= :input (::jobs/resource-role def)))
        (t/is (fn? (::jobs/handler def)))))

    (t/testing "the params are a closed contract"
      (let [def     (jobs/get-job-def defs :export-binfile)
            file-id (uuid/next)]
        (t/is (true? ((::jobs/validator def)
                      {:file-ids #{file-id} :export-type :detach-libraries})))
        (t/testing "a job id never travels in params"
          (t/is (false? ((::jobs/validator def)
                         {:file-ids #{file-id} :export-type :detach-libraries
                          :job-id (uuid/next)}))))))))

(t/deftest the-export-handler-runs-through-the-registry
  (let [profile (th/create-profile* 1)
        file-id (first (:file-ids (import-fixture! profile)))
        job-id  (make-job! (:id profile) nil)
        result  (jobs/invoke (-> th/*system*
                                 (assoc ::jobs/name :export-binfile)
                                 (assoc ::jobs/params {:file-ids    #{file-id}
                                                       :export-type :detach-libraries})
                                 (assoc ::jobs/context (job-context job-id))))]

    (t/testing "the handler answers with the completion envelope of the job"
      (t/is (uuid? (:resource-id result)))
      (t/is (some? (get-in result [:result :resource-uri]))))

    (t/testing "the handler reported progress without knowing the job id"
      (t/is (pos? (count (progress-events job-id)))))))

(t/deftest the-import-handler-runs-through-the-registry
  (let [profile  (th/create-profile* 1)
        staged   (js/put-resource th/*system* (:id profile)
                                  {:content  (sto/content (fixture-path))
                                   :filename "package.penpot"
                                   :mtype    "application/zip"})
        job-id   (make-job! (:id profile) (:resource-id staged))
        result   (jobs/invoke (-> th/*system*
                                  (assoc ::jobs/name :import-binfile)
                                  (assoc ::jobs/params {:project-id (:default-project-id profile)
                                                        :name       "imported"
                                                        :version    3})
                                  (assoc ::jobs/context (job-context job-id))))]

    (t/testing "the package of the job was imported"
      (t/is (pos? (count (:file-ids result)))))
    (t/testing "the handler reported progress without knowing the job id"
      (t/is (pos? (count (progress-events job-id)))))))
