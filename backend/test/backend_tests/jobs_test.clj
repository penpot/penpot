;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.jobs-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [app.msgbus :as mbus]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)

(defn- test-fixture [next]
  (th/database-reset next))

(t/use-fixtures :each test-fixture)

(defn- raised
  "Run `f` and answer with the exception it raised, or nil when it did not."
  [f]
  (try (f) nil (catch Throwable cause cause)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB-DEF (plain handler + precompiled init-key)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn echo-handler
  "Plain handler function, importable and testable without integrant.
  Dual behavior: when invoked in-process (no job-id in cfg), heartbeat/progress
  are no-ops. When invoked via the runner (with job-id in cfg), they write to
  the job row. This allows the same handler to be tested both ways."
  [cfg params]
  (when (::jobs/job-id cfg)
    (jobs/heartbeat cfg)
    (jobs/heartbeat cfg :progress {:stage :half
                                   :counters {:work {:current 1 :total 2}}}))
  params)

(def schema:echo-params
  [:map
   [:text ::sm/text]
   [:object :keyword]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]])

(defmethod ig/init-key ::echo-job
  [_ cfg]
  {::jobs/name      :echo
   ::jobs/schema    schema:echo-params
   ::jobs/handler   (fn [_context params]
                      (echo-handler cfg params))
   ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:echo-params)})

(defn- get-job-defs
  []
  (-> {::jobs/defs {:echo (ig/ref ::echo-job)}
       ::echo-job  {::db/pool th/*pool*}}
      (ig/expand)
      (ig/init)
      (get ::jobs/defs)))

(defn- make-cfg
  [defs]
  {::jobs/defs   defs
   ::db/pool     th/*pool*
   ::mtx/metrics (get th/*system* :app.metrics/metrics)})

(defn- make-params
  []
  {:text      "hello"
   :object    :snapshot
   :deleted-at (ct/now)
   :id        (uuid/next)
   :file-id   (uuid/next)})

(defn- insert-job-statement?
  "True when a `db/exec-one!` call is the job INSERT, whatever the
  argument shape (a `[sql & params]` vector or a bare SQL string)."
  [args]
  (let [stmt (second args)
        sql  (if (string? stmt) stmt (first stmt))]
    (and (string? sql) (str/starts-with? sql "insert into job"))))

(def ^:private test-error
  {:type :internal
   :code :boom
   :hint "boom"})

(defn- get-progresss
  "Progress events of a job, oldest first, with decoded payloads."
  [job-id]
  (->> (th/db-exec! ["SELECT payload FROM job_event
                      WHERE job_id = ? AND kind = 'progress'
                      ORDER BY created_at ASC, id ASC"
                     job-id])
       (mapv #(jobs/decode-progress (:payload %)))))

(defn- get-events
  "Every event of a job, in insertion order."
  [job-id]
  (->> (th/db-exec! ["SELECT kind, payload FROM job_event
                      WHERE job_id = ? ORDER BY id ASC" job-id])
       (mapv (fn [{:keys [kind payload]}]
               {:kind kind :payload (db/decode-json-pgobject payload)}))))

(defn- get-outcomes
  "Outcomes of the end events of a job, oldest first."
  [job-id]
  (mapv (comp :outcome :payload) (filter #(= "end" (:kind %)) (get-events job-id))))

(defn- get-kinds
  [job-id]
  (mapv :kind (get-events job-id)))

(defn- fake-msgbus
  "A minimal msgbus that records every publication on the atom."
  [messages]
  (reify mbus/IMsgBus
    (-sub [_ _ _] nil)
    (-pub [_ topic message]
      (swap! messages conj {:topic topic :message message})
      nil)
    (-purge [_ _] nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest submit-persists-optional-profile-and-resource-references
  (let [cfg          (make-cfg (get-job-defs))
        profile-id   (:id (th/create-profile* 1))
        resource-id  (uuid/next)]
    ;; the resource reference points to a real storage object: the column
    ;; is a foreign key kept for garbage collection
    (th/db-insert! :storage-object {:id resource-id :backend "test"})
    (let [internal-id  (jobs/submit cfg {::jobs/name   :echo
                                         ::jobs/params (make-params)})
          owned-id     (jobs/submit cfg {::jobs/name        :echo
                                         ::jobs/params      (make-params)
                                         ::jobs/profile-id  profile-id
                                         ::jobs/resource-id resource-id})
          internal-row (jobs/get-job cfg internal-id)
          owned-row    (jobs/get-job cfg owned-id)]

      (t/testing "profile_id stays nullable for internal jobs"
        (t/is (nil? (:profile-id internal-row)))
        (t/is (nil? (:resource-id internal-row))))

      (t/testing "an explicit profile is persisted as the owner of the job"
        (t/is (= profile-id (:profile-id owned-row))))

      (t/testing "the resource reference is a column, not part of params"
        (t/is (= resource-id (:resource-id owned-row)))
        (t/is (not (contains? (:params owned-row) :resource-id)))))))

(t/deftest submit-rejects-invalid-profile-and-resource-references
  (let [cfg (make-cfg (get-job-defs))]
    (t/testing "a non uuid profile-id is rejected before the insert"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name       :echo
                                       ::jobs/params     (make-params)
                                       ::jobs/profile-id "not-a-uuid"}))))
    (t/testing "a non uuid resource-id is rejected before the insert"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name        :echo
                                       ::jobs/params      (make-params)
                                       ::jobs/resource-id "not-a-uuid"}))))
    (t/testing "no job row was created by the rejected submits"
      (t/is (= 0 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job"])))))))

(t/deftest submit-persists-an-expiry-date
  (let [cfg        (make-cfg (get-job-defs))
        expires-at (ct/plus (ct/now) (ct/duration {:days 7}))]

    (t/testing "an explicit expiry is persisted as the retention of the job"
      (let [job-id (jobs/submit cfg {::jobs/name       :echo
                                     ::jobs/params     (make-params)
                                     ::jobs/expires-at expires-at})
            row    (jobs/get-job cfg job-id)]
        (t/is (= (inst-ms expires-at) (inst-ms (:expires-at row))))))

    (t/testing "without the option the column stays nil, as before"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
            row    (jobs/get-job cfg job-id)]
        (t/is (nil? (:expires-at row)))))))

(t/deftest submit-rejects-an-invalid-expiry-date
  (let [cfg (make-cfg (get-job-defs))]
    (t/testing "a value that is not an instant is rejected before the insert"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name       :echo
                                       ::jobs/params     (make-params)
                                       ::jobs/expires-at "not-an-instant"}))))
    (t/testing "no job row was created by the rejected submit"
      (t/is (= 0 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job"])))))))

(t/deftest progress-report-validates-its-payload
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "stage is mandatory"
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id
                                     :progress {:counters {:work {:current 1}}}))))

    (t/testing "stage must be a keyword, not a label"
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id
                                     :progress {:stage "work"}))))

    (t/testing "unknown keys are rejected, at the top and inside a counter"
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id
                                     :progress {:stage :work :step 1})))
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id
                                     :progress {:stage :work
                                                :counters {:work {:current 1
                                                                  :step 1}}}))))

    (t/testing "a counter current must not be negative"
      (t/is (thrown-with-msg? Exception #"negative"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:stage :work
                                                         :counters {:work {:current -1}}}))))

    (t/testing "a counter total must be positive"
      (t/is (thrown-with-msg? Exception #"positive"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:stage :work
                                                         :counters {:work {:current 0
                                                                           :total 0}}}))))

    (t/testing "a counter current must not be greater than its total"
      (t/is (thrown-with-msg? Exception #"not lower than current"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:stage :work
                                                         :counters {:work {:current 5
                                                                           :total 4}}}))))

    (t/testing "keys are limited to a name and not a payload"
      (t/is (thrown-with-msg? Exception #"64"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:stage (keyword (apply str (repeat 65 "x")))}))))

    (t/testing "a milestone without counters is accepted"
      (t/is (= 2 (jobs/heartbeat cfg :job-id job-id :progress {:stage :relations}))))

    (t/testing "a milestone with counters is accepted and stored as it is"
      (swap! jobs/progresses dissoc job-id)
      (swap! jobs/heartbeats dissoc job-id)
      (t/is (= 2 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:stage :pages
                                            :counters {:files {:current 2 :total 5}
                                                       :pages {:current 3 :total 8}}})))
      (t/is (= [{:stage :relations}
                {:stage :pages
                 :counters {:files {:current 2 :total 5}
                            :pages {:current 3 :total 8}}}]
               (get-progresss job-id))))

    (t/testing "a rejected report never reaches the durable log"
      (t/is (= 2 (count (get-progresss job-id)))))))

(t/deftest progress-event-publishes-msgbus-for-profile-jobs
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        messages   (atom [])
        job-cfg    (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id     (jobs/submit job-cfg {::jobs/name       :echo
                                         ::jobs/params     (make-params)
                                         ::jobs/profile-id profile-id})]
    (t/is (pos? (jobs/heartbeat job-cfg :job-id job-id
                                :progress {:stage :pages
                                           :counters {:files {:current 3 :total 7}}})))
    (t/testing "the event is published on the topic of the job profile"
      (t/is (= 1 (count @messages)))
      (let [{:keys [topic message]} (first @messages)]
        (t/is (= profile-id topic))
        (t/is (= :job-event (:type message)))
        (t/is (= job-id (:job-id message)))
        (t/is (= profile-id (:profile-id message)))
        (t/is (= "progress" (:kind message)))
        (t/is (= {:stage :pages
                  :counters {:files {:current 3 :total 7}}}
                 (:payload message)))
        (t/is (some? (:event-id message)))
        (t/is (some? (:created-at message)))))))

(t/deftest progress-event-of-internal-job-is-not-published
  (let [cfg      (make-cfg (get-job-defs))
        messages (atom [])
        job-cfg  (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id   (jobs/submit job-cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
    (t/is (pos? (jobs/heartbeat job-cfg :job-id job-id :progress {:stage :work})))
    (t/testing "the event is stored but nothing is published"
      (t/is (= 1 (count (get-progresss job-id))))
      (t/is (= [] @messages)))))

(t/deftest progress-event-of-profile-job-requires-msgbus
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        job-id     (jobs/submit cfg {::jobs/name       :echo
                                     ::jobs/params     (make-params)
                                     ::jobs/profile-id profile-id})]
    (t/testing "the forced route propagates the missing msgbus"
      (t/is (thrown-with-msg? Exception #"require ::mbus/msgbus"
                              (jobs/heartbeat cfg
                                              :job-id job-id
                                              :progress {:stage :work}
                                              ::jobs/force? true)))
      (t/is (= [] (get-progresss job-id))
            "the event must not be stored without its notification"))

    (t/testing "the handler route logs the failure and keeps the job alive"
      (th/db-update! :job {:modified-at (ct/in-past {:minutes 5})} {:id job-id})
      (swap! jobs/heartbeats dissoc job-id)
      (t/is (= 1 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:stage :work})))
      (t/is (= [] (get-progresss job-id))))))

(t/deftest submit-validates-params-with-job-schema
  (let [cfg (make-cfg (get-job-defs))]
    (t/is (thrown-with-msg? Exception #"check error"
                            (jobs/submit cfg {::jobs/name   :echo
                                              ::jobs/params {:text "hello"
                                                             :object :snapshot
                                                             :deleted-at (ct/now)
                                                             :id (uuid/next)
                                                             :file-id "not-an-uuid"}}))))

  (t/testing "missing job definition raises a clear error"
    (let [cfg (make-cfg (get-job-defs))]
      (t/is (thrown-with-msg? Exception #"no job definition"
                              (jobs/submit cfg {::jobs/name   :unknown
                                                ::jobs/params {}})))))

  (t/testing "the job name is a keyword, and only a keyword"
    (let [cfg (make-cfg (get-job-defs))]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (jobs/submit cfg {::jobs/name   "echo"
                                       ::jobs/params {}})))
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (jobs/invoke (assoc cfg
                                         ::jobs/name "echo"
                                         ::jobs/params {})))))))

(t/deftest submit-persists-row-with-json-params
  (let [cfg   (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/queue  :test
                                 ::jobs/delay  1000
                                 ::jobs/priority 200
                                 ::jobs/label  "test-label"
                                 ::jobs/max-retries 5})
        row    (jobs/get-job cfg job-id)]

    (t/is (uuid? job-id))
    (t/is (= "echo" (:name row)))
    (t/is (= (cf/get :tenant) (:tenant row)))
    (t/is (= "test" (:queue row)))
    (t/is (= "test-label" (:label row)))
    (t/is (= 200 (:priority row)))
    (t/is (= 5 (:max-retries row)))
    (t/is (= "new" (:status row)))

    (t/testing "scheduled_at respects the submitted delay"
      (let [delay (ct/duration {:seconds 1})]
        (t/is (pos? (- (inst-ms (:scheduled-at row)) (inst-ms (ct/now))))
              (str "scheduled-at should be in the future: "
                   (pr-str (:scheduled-at row))))))

    (t/testing "the row exposes params and no legacy columns"
      (t/is (some? (:params row)))
      (t/is (not (contains? row :props)))
      (t/is (not (contains? row :progress)))
      (t/is (not (contains? row :target))))

    (t/testing "params are stored as plain JSON (no transit tags) and decoded"
      (let [params (:params row)]
        (t/is (map? params))
        (t/is (nil? (some-> (th/db-exec-one! ["SELECT params::text FROM job WHERE id = ?"
                                              job-id])
                            :params
                            (str/index-of "~#"))))

        (t/testing "round-trip: decoded values have proper clojure types"
          (let [decoded (jobs/decode-params (jobs/get-job-def (get cfg ::jobs/defs) :echo)
                                            (:params row))]
            (t/is (keyword? (:object decoded)))
            (t/is (= :snapshot (:object decoded)))
            (t/is (uuid? (:id decoded)))
            (t/is (uuid? (:file-id decoded)))
            (t/is (ct/inst? (:deleted-at decoded)))))))))

(t/deftest submit-dedupe-replaces-not-due-new-rows
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true
                                 ::jobs/delay  10000})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true
                                 ::jobs/delay  10000})]

    (t/is (uuid? id1))
    (t/is (uuid? id2))
    (t/is (not= id1 id2))

    (t/testing "the first new row (not due yet) is removed"
      (t/is (nil? (jobs/get-job cfg id1)))

      (t/testing "and only the new row remains"
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                            WHERE label = ?" label]))))))))

(t/deftest submit-dedupe-keeps-due-and-running-rows
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-active-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label})
        _      (th/db-update! :job {:status "running"} {:id id1})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label])))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-due-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label})
        _      (th/db-update! :job {:scheduled-at (ct/minus (ct/now)
                                                            (ct/duration {:seconds 5}))}
                              {:id id1})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label]))))))

(t/deftest submit-dedupe-leaves-another-tenants-rows-alone
  ;; The DELETE matches name, queue and label. Without the tenant in the
  ;; match it would reach into another instance's not-started jobs.
  (let [cfg        (make-cfg (get-job-defs))
        params     (make-params)
        label      "dedupe-shared-label"
        foreign-id (uuid/next)]
    (th/db-insert! :job {:id           foreign-id
                         :name         "echo"
                         :tenant       "other-tenant"
                         :queue        "default"
                         :label        label
                         :params       (db/json params)
                         :status       "new"
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :scheduled-at (ct/plus (ct/now) (ct/duration {:seconds 10}))
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    (jobs/submit cfg {::jobs/name   :echo
                      ::jobs/params params
                      ::jobs/queue  :default
                      ::jobs/label  label
                      ::jobs/dedupe true
                      ::jobs/delay  10000})
    (t/is (some? (jobs/get-job cfg foreign-id))
          "another tenant's matching job must survive our dedupe")
    (t/testing "and only our own row was written"
      (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                          WHERE label = ?
                                            AND tenant = ?"
                                         label (cf/get :tenant)])))))))

(t/deftest submit-dedupe-rolls-back-on-insert-failure
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        opts   {::jobs/name   :echo
                ::jobs/params params
                ::jobs/dedupe true
                ::jobs/label  "atomic-label"}
        kept   (jobs/submit cfg opts)
        orig   @#'db/exec-one!]
    ;; fault the INSERT: the DELETE before it must roll back too
    (alter-var-root #'db/exec-one!
                    (constantly (fn [& args]
                                  (when (insert-job-statement? args)
                                    (throw (ex-info "boom" {})))
                                  (apply orig args))))
    (try
      (t/is (thrown? Exception (jobs/submit cfg opts)))
      (t/testing "the original row survives, no duplicate left behind"
        (t/is (some? (jobs/get-job cfg kept)))
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job WHERE label = ?" "atomic-label"])))))
      (finally
        (alter-var-root #'db/exec-one! (constantly orig))))))

(t/deftest submit-dedupe-atomic-on-autocommit-conn
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        opts   {::jobs/name   :echo
                ::jobs/params params
                ::jobs/dedupe true
                ::jobs/label  "atomic-autocommit-label"}
        kept   (jobs/submit cfg opts)
        orig   @#'db/exec-one!]
    ;; a raw connection outside any transaction: DELETE+INSERT must
    ;; still share one transaction opened on it
    (with-open [conn (db/open th/*pool*)]
      (alter-var-root #'db/exec-one!
                      (constantly (fn [& args]
                                    (when (insert-job-statement? args)
                                      (throw (ex-info "boom" {})))
                                    (apply orig args))))
      (try
        (t/is (thrown? Exception
                       (jobs/submit (assoc cfg ::db/conn conn) opts)))
        (t/testing "the original row survives, no duplicate left behind"
          (t/is (some? (jobs/get-job cfg kept)))
          (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job WHERE label = ?" "atomic-autocommit-label"])))))
        (finally
          (alter-var-root #'db/exec-one! (constantly orig)))))))

(t/deftest submit-requires-metrics-on-bare-connectable
  (let [defs (get-job-defs)
        prev @@#'jobs/defs-registry]
    (try
      (reset! @#'jobs/defs-registry defs)
      (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                              (jobs/submit th/*pool* {::jobs/name   :echo
                                                      ::jobs/params (make-params)})))
      (finally
        (reset! @#'jobs/defs-registry prev)))))

(t/deftest plain-handler-is-testable-without-integrant
  (let [params (make-params)]
    (t/is (= params (echo-handler {} params))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params})]
    (t/testing "handler receives cfg with job-id context for heartbeats"
      (t/is (= params (echo-handler (assoc cfg ::jobs/job-id job-id)
                                    params))))))

(t/deftest submit-strips-rollback-testing-flag-from-params
  (let [cfg    (make-cfg (get-job-defs))
        params (assoc (make-params) :rollback? true)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params})]
    (t/testing "durable rows never carry the in-process escape hatch"
      (t/is (nil? (:rollback? (:params (jobs/get-job cfg job-id))))))))

(t/deftest heartbeat-respects-throttle
  (let [cfg   (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "first heartbeat writes"
      ;; Backdate modified-at so the beat is unambiguous: the assertion
      ;; compares against the value we set, not against created-at, which
      ;; can land on the same millisecond.
      (let [backdated (ct/in-past {:seconds 5})]
        (th/db-update! :job {:modified-at backdated} {:id job-id})
        (t/is (= 1 (jobs/heartbeat cfg :job-id job-id)))
        (let [row (jobs/get-job cfg job-id)]
          (t/is (> (inst-ms (:modified-at row)) (inst-ms backdated))))))

    (t/testing "immediate second heartbeat does not write (throttled)"
      (let [row1 (jobs/get-job cfg job-id)
            _    (jobs/heartbeat cfg :job-id job-id)
            row2 (jobs/get-job cfg job-id)]
        (t/is (= (inst-ms (:modified-at row1))
                 (inst-ms (:modified-at row2))))))))

(t/deftest heartbeat-interrupts-on-terminal-states
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "a terminal state raises the interrupt instead of touching the row"
      (th/db-update! :job {:status "completed"
                           :modified-at (ct/in-past {:days 10})}
                     {:id job-id})
      (swap! @#'jobs/heartbeats dissoc job-id)
      (let [before (jobs/get-job cfg job-id)
            cause  (raised #(jobs/heartbeat cfg :job-id job-id))]
        (t/is (= :interrupt (th/ex-type cause)))
        (t/is (= :job-interrupted (th/ex-code cause)))
        (t/is (= job-id (:job-id (ex-data cause))))
        (t/is (= "completed" (:status (ex-data cause))))
        (t/is (= (inst-ms (:modified-at before))
                 (inst-ms (:modified-at (jobs/get-job cfg job-id)))))))))

(t/deftest heartbeat-interrupts-when-the-row-is-gone
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (th/db-force-delete :job {:id job-id})
    (let [cause (raised #(jobs/heartbeat cfg :job-id job-id))]
      (t/is (= :interrupt (th/ex-type cause)))
      (t/is (= :job-interrupted (th/ex-code cause)))
      (t/is (= "gone" (:status (ex-data cause)))))))

(t/deftest a-transient-progress-failure-is-not-an-interrupt
  "The handler path logs and ignores a progress insert that fails, but an
  interrupt must not be swallowed with it: only an interrupt means the job
  is gone."
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (swap! jobs/progresses dissoc job-id)
    (swap! jobs/heartbeats dissoc job-id)
    ;; the insert is the boundary that fails: the job itself is active, so
    ;; the beat answers with the touch it did write instead of raising
    (with-redefs-fn {#'jobs/report-progress (fn [& _] (throw (ex-info "boom" {})))}
      #(t/is (= 1 (jobs/heartbeat cfg :job-id job-id :progress {:stage :work}))))
    (t/is (= [] (get-progresss job-id)))))

(t/deftest progress-events-respect-throttle-and-interrupt-on-terminal-states
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "first progress report appends a progress event"
      (t/is (= 2 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:stage :pages
                                            :counters {:pages {:current 1 :total 10}}})))
      (t/is (= [{:stage :pages :counters {:pages {:current 1 :total 10}}}]
               (get-progresss job-id))))

    (t/testing "immediate second progress report is throttled"
      (t/is (= 0 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:stage :pages
                                            :counters {:pages {:current 2 :total 10}}})))
      (t/is (= [{:stage :pages :counters {:pages {:current 1 :total 10}}}]
               (get-progresss job-id)))

      (t/testing "after the throttle window elapses it appends again"
        (swap! @#'jobs/progresses
               (fn [m]
                 (update-in m [job-id]
                            #(ct/minus %
                                       (ct/duration {:seconds 2})))))
        (jobs/heartbeat cfg :job-id job-id
                        :progress {:stage :pages
                                   :counters {:pages {:current 3 :total 10}}})
        (t/is (= [{:stage :pages :counters {:pages {:current 1 :total 10}}}
                  {:stage :pages :counters {:pages {:current 3 :total 10}}}]
                 (get-progresss job-id))))))

  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "terminal states never get a progress event"
      (th/db-update! :job {:status "completed"} {:id job-id})
      (swap! @#'jobs/progresses dissoc job-id)
      (swap! @#'jobs/heartbeats dissoc job-id)
      (let [cause (raised #(jobs/heartbeat cfg :job-id job-id
                                           :progress {:stage :work}))]
        (t/is (= :interrupt (th/ex-type cause)))
        (t/is (= [] (get-progresss job-id)))))))

(t/deftest throttle-prune-removes-stale-entries-keeps-fresh
  "When the throttle map exceeds prune-threshold, stale entries (older than
  prune-window) are removed and fresh entries are kept."
  (let [cfg        (make-cfg (get-job-defs))
        job-id-1   (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
        job-id-2   (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
        now        (ct/now)
        stale-time (ct/minus now (ct/duration {:hours 2}))  ;; older than 1h window
        fresh-time (ct/minus now (ct/duration {:minutes 30}))] ;; within 1h window

    ;; Fill the heartbeats map past the threshold with one stale and one fresh entry
    (with-redefs [jobs/prune-threshold 0]  ;; force prune on next call
      (reset! @#'jobs/heartbeats
              {job-id-1 stale-time
               job-id-2 fresh-time})

      ;; Trigger a heartbeat for a new job (should trigger prune)
      (let [job-id-3 (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
        (jobs/heartbeat cfg :job-id job-id-3)

        ;; After prune: stale entry (job-id-1) should be gone, fresh (job-id-2) should remain
        (let [state @jobs/heartbeats]
          (t/is (not (contains? state job-id-1)) "stale entry removed")
          (t/is (contains? state job-id-2) "fresh entry kept")
          (t/is (contains? state job-id-3) "new entry added"))))))

(t/deftest cancel-skips-terminal-jobs
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "pending job can be cancelled"
      (t/is (= 1 (jobs/cancel cfg job-id)))
      (t/is (= "cancelled" (:status (jobs/get-job cfg job-id))))
      (t/is (= ["cancelled"] (get-outcomes job-id)))
      (t/testing "already cancelled job is not affected again"
        (t/is (zero? (jobs/cancel cfg job-id)))
        (t/is (= ["cancelled"] (get-outcomes job-id))))))
  (doseq [status ["completed" "failed"]]
    (t/testing (str status " job cannot be cancelled")
      (let [cfg    (make-cfg (get-job-defs))
            job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})]
        (th/db-update! :job {:status status} {:id job-id})
        (t/is (zero? (jobs/cancel cfg job-id)))
        (t/is (= status (:status (jobs/get-job cfg job-id))))
        (t/is (= [] (get-outcomes job-id)))))))

(t/deftest cancel-affects-pending-and-running-jobs
  (let [cfg (make-cfg (get-job-defs))]
    (doseq [status ["scheduled" "retry" "running"]]
      (t/testing (str status " job can be cancelled")
        (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
          (th/db-update! :job {:status status} {:id job-id})
          (t/is (= 1 (jobs/cancel cfg job-id)))
          (t/is (= "cancelled" (:status (jobs/get-job cfg job-id))))
          (t/is (= ["cancelled"] (get-outcomes job-id))))))))

(t/deftest get-user-status-maps-internal-statuses
  (t/are [status expected] (= expected (jobs/get-user-status status))
    "new"       "pending"
    "scheduled" "pending"
    "retry"     "pending"
    :new        "pending"
    "running"   "running"
    "completed" "completed"
    "failed"    "failed"
    "cancelled" "failed"
    "aborted"   "failed"))

(t/deftest job-defs-registry-validates-definitions
  (t/testing "valid job-def map passes the assert"
    (t/is (some? (get-job-defs))))

  (t/testing "registry name mismatch is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name      :other
                                           ::jobs/schema    schema:echo-params
                                           ::jobs/handler   (fn [_context params]
                                                              (echo-handler nil params))
                                           ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
                                           ::jobs/validator (sm/validator schema:echo-params)}}}))))

  (t/testing "missing decoder/validator is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name    :echo
                                           ::jobs/schema  schema:echo-params
                                           ::jobs/handler (fn [_context params]
                                                            params)}}})))))

(t/deftest job-def-carries-optional-family-and-resource-role
  (let [job-def {::jobs/name          :echo
                 ::jobs/family        :export
                 ::jobs/resource-role :output
                 ::jobs/schema        schema:echo-params
                 ::jobs/handler       (fn [_context params] params)
                 ::jobs/decoder       (sm/decoder schema:echo-params sm/json-transformer)
                 ::jobs/validator     (sm/validator schema:echo-params)}]

    (t/testing "the metadata is accepted and readable from the registry"
      (let [defs (-> (ig/init {::jobs/defs {:echo job-def}})
                     (get ::jobs/defs))
            echo (jobs/get-job-def defs :echo)]
        (t/is (= :export (::jobs/family echo)))
        (t/is (= :output (::jobs/resource-role echo)))))

    (t/testing "a family is a keyword, not a free string"
      (t/is (thrown? Exception
                     (ig/init {::jobs/defs {:echo (assoc job-def
                                                         ::jobs/family "export")}}))))

    (t/testing "a resource role is a keyword too"
      (t/is (thrown? Exception
                     (ig/init {::jobs/defs {:echo (assoc job-def
                                                         ::jobs/resource-role "output")}}))))))

(t/deftest generic-schema-round-trip-preserves-type-sensitive-fields
  "For each registered job-def, verify that type-sensitive fields (uuids, insts)
  survive the JSON round-trip (db/json → decode). This catches the F2 class of
  bug where uuid types are lost during the transit→JSON switch."
  (let [defs (get-job-defs)]
    (t/is (pos? (count defs)) "should have at least one job-def")

    ;; Test the echo job-def which we know exists in the test registry
    (t/testing "echo job-def preserves types through JSON round-trip"
      (let [echo-def (get defs :echo)
            _        (t/is (some? echo-def) "echo job-def should exist")
            ;; Create a representative params map with type-sensitive fields
            sample-params {:text "test"
                           :object :snapshot
                           :deleted-at (ct/now)
                           :id (uuid/random)
                           :file-id (uuid/random)}

            ;; Round-trip through JSON (simulates the job table storage)
            ;; db/json returns a PGobject, which is what decode-params expects
            pg-obj  (db/json sample-params)
            decoded (jobs/decode-params echo-def pg-obj)]

        ;; Verify type-sensitive fields survived
        (t/is (uuid? (:id decoded)) "id should be uuid after decode")
        (t/is (uuid? (:file-id decoded)) "file-id should be uuid after decode")
        (t/is (inst? (:deleted-at decoded)) "deleted-at should be inst after decode")))))

(t/deftest invoke-falls-back-to-global-registry
  "Verify that invoke! uses the global registry fallback when ::defs is not
  on the cfg, consistent with submit!."
  ;; Rebind the private registry atom instead of overwriting global state:
  ;; it auto-restores on exit, so no later test can observe it. Note
  ;; get-job-defs itself populates the global via ig/init (established
  ;; pattern, also used by the sibling tests); the snapshot below is
  ;; taken after that, so it pins exactly this test's rebinding.
  (let [defs   (get-job-defs)
        before @@#'jobs/defs-registry]
    (with-redefs [jobs/defs-registry (atom defs)]
      ;; cfg WITHOUT ::jobs/defs to exercise the fallback; the echo
      ;; job-def is known to exist in the rebound registry
      (let [cfg    {::db/pool th/*pool*}
            result (jobs/invoke (assoc cfg ::jobs/name :echo
                                       ::jobs/params (make-params)))]
        ;; Should not throw; should find the job-def via the fallback.
        ;; The result is the params map (echo-handler returns params).
        (t/is (some? result))))
    (t/testing "global registry untouched"
      (t/is (= before @@#'jobs/defs-registry)))))

(t/deftest heartbeat-and-progress-noop-when-job-id-nil
  "When *job-id* is nil (in-process invoke! without a job row),
  heartbeat! and progress! are no-ops — they do not write to the
  database or update the throttle atoms."
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "heartbeat! is a no-op when *job-id* is nil"
      (let [row-before (jobs/get-job cfg job-id)]
        (binding [jobs/*job-id* nil]
          (jobs/heartbeat cfg))
        (let [row-after (jobs/get-job cfg job-id)]
          (t/is (= (inst-ms (:modified-at row-before))
                   (inst-ms (:modified-at row-after)))
                "modified-at should not change"))))

    (t/testing "progress reporting is a no-op when *job-id* is nil"
      (binding [jobs/*job-id* nil]
        (t/is (nil? (jobs/heartbeat cfg :progress {:stage :work}))))
      (t/is (= [] (get-progresss job-id))
            "no progress event should be stored"))))

(t/deftest progress-interrupts-on-terminal-states
  "A progress report never stores an event when the job is already in a
  terminal state (completed, failed, cancelled, aborted): the row is locked and
  checked before the insert, so a report can never race a terminal
  transition, and the beat raises the interrupt instead."
  (let [cfg (make-cfg (get-job-defs))]
    (doseq [status ["completed" "failed" "cancelled" "aborted"]]
      (t/testing (str "progress interrupts on " status " status")
        (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
          (th/db-update! :job {:status status} {:id job-id})
          ;; reset the throttles so should-write? would allow the writes
          (swap! jobs/progresses dissoc job-id)
          (swap! jobs/heartbeats dissoc job-id)
          (binding [jobs/*job-id* job-id]
            (let [cause (raised #(jobs/heartbeat cfg :progress {:stage :work}))]
              (t/is (= :interrupt (th/ex-type cause)))
              (t/is (= status (:status (ex-data cause))))))
          (t/is (= [] (get-progresss job-id))))))))

(t/deftest defs-halt-clears-module-registry
  (let [prev @@#'jobs/defs-registry]
    ;; init populates the module registry
    (get-job-defs)
    (t/is (contains? @@#'jobs/defs-registry :echo))
    (try
      (ig/halt-key! ::jobs/defs nil)
      (t/is (= {} @@#'jobs/defs-registry))
      (finally
        (reset! @#'jobs/defs-registry prev)))))

(t/deftest heartbeat-bypasses-caller-transaction
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    ;; backdate so the beat is strictly greater (no same-millis flake)
    (th/db-update! :job {:modified-at (ct/in-past {:minutes 5})} {:id job-id})
    ;; beat inside a transaction that is rolled back: the beat must
    ;; still be visible (it went through the pool, not the tx)
    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/heartbeat (assoc cfg ::db/conn conn) :job-id job-id)))
    (let [row (jobs/get-job cfg job-id)]
      (t/is (> (inst-ms (:modified-at row))
               (inst-ms (ct/in-past {:minutes 5})))))))

(t/deftest progress-bypasses-caller-transaction
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/heartbeat (assoc cfg ::db/conn conn)
                                  :job-id job-id
                                  :progress {:stage :work})))
    (t/is (= [{:stage :work}] (get-progresss job-id)))))

(t/deftest progress-notification-survives-a-rolled-back-caller
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        messages   (atom [])
        job-cfg    (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id     (jobs/submit job-cfg {::jobs/name       :echo
                                         ::jobs/params     (make-params)
                                         ::jobs/profile-id profile-id})]
    ;; the report owns its transaction, so it commits and publishes even
    ;; though the transaction that triggered it rolls back
    (db/tx-run! (assoc job-cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/heartbeat (assoc job-cfg ::db/conn conn)
                                  :job-id job-id
                                  :progress {:stage :work})))
    (t/testing "the event is stored"
      (t/is (= [{:stage :work}] (get-progresss job-id))))
    (t/testing "and the notification went out with its own commit"
      (t/is (= 1 (count @messages)))
      (t/is (= "progress" (:kind (:message (first @messages))))))))

(t/deftest heartbeat-requires-a-cfg-map
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "a bare pool is a caller error, not something to accommodate"
      (t/is (thrown? Exception
                     (jobs/heartbeat th/*pool*
                                     :job-id job-id
                                     :progress {:stage :work}))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER CONTEXT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-row
  "A job row as the runner and make-context see it."
  [overrides]
  (merge {:id          (uuid/next)
          :name        "echo"
          :tenant      (cf/get :tenant)
          :queue       "default"
          :label       nil
          :profile-id  nil
          :resource-id nil
          :retry-num   0
          :max-retries 3}
         overrides))

(t/deftest make-context-selects-exactly-the-agreed-keys
  (let [context (jobs/make-context (mk-row {}))]
    (t/is (= #{:id :name :label :resource-id :profile-id} (set (keys context))))
    (t/testing "the queue is a routing detail of the dispatcher, not context"
      (t/is (not (contains? context :queue))))
    (t/testing "nor is the tenant that owns the queue"
      (t/is (not (contains? context :tenant))))
    (t/testing "the retry keys belong to the runner, not to a handler"
      (t/is (not (contains? context :retry-num)))
      (t/is (not (contains? context :max-retries)))
      (t/is (not (contains? context :attempt))))
    (t/testing "the context is a plain map, not the row"
      (t/is (not= (set (keys (mk-row {}))) (set (keys context)))))))

(t/deftest make-context-normalizes-an-empty-label
  (t/testing "the empty label is the submit default for no label"
    (t/is (nil? (:label (jobs/make-context (mk-row {:label ""})))))
    (t/is (nil? (:label (jobs/make-context (mk-row {:label nil}))))))
  (t/testing "a real label is carried as is"
    (t/is (= "my-label" (:label (jobs/make-context (mk-row {:label "my-label"})))))))

(t/deftest make-context-always-carries-the-resource-reference
  (t/testing "the key exists even when there is no resource"
    (let [context (jobs/make-context (mk-row {}))]
      (t/is (contains? context :resource-id))
      (t/is (nil? (:resource-id context)))))
  (t/testing "a resource reference is carried as is"
    (let [resource-id (uuid/next)]
      (t/is (= resource-id
               (:resource-id (jobs/make-context (mk-row {:resource-id resource-id}))))))))

(t/deftest make-context-carries-the-job-owner
  (t/testing "the key exists even when the job has no owner"
    (let [context (jobs/make-context (mk-row {}))]
      (t/is (contains? context :profile-id))
      (t/is (nil? (:profile-id context)))))
  (t/testing "the owner of a user job is carried as is"
    (let [profile-id (uuid/next)]
      (t/is (= profile-id
               (:profile-id (jobs/make-context (mk-row {:profile-id profile-id}))))))))

(t/deftest make-context-rejects-invalid-rows
  (t/testing "a missing id is rejected"
    (t/is (thrown? Exception (jobs/make-context (mk-row {:id nil})))))
  (t/testing "a missing name is rejected"
    (t/is (thrown? Exception (jobs/make-context (mk-row {:name nil})))))
  (t/testing "a non uuid owner is rejected"
    (t/is (thrown? Exception (jobs/make-context (mk-row {:profile-id "nope"})))))
  (t/testing "a non uuid resource reference is rejected"
    (t/is (thrown? Exception (jobs/make-context (mk-row {:resource-id "nope"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IN-PROCESS INVOCATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def received-contexts (atom []))

(defn- capture-defs
  "Job-defs whose handler records the context it receives."
  []
  {:echo {::jobs/name      :echo
          ::jobs/schema    schema:echo-params
          ::jobs/handler   (fn [context params]
                             (swap! received-contexts conj context)
                             params)
          ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
          ::jobs/validator (sm/validator schema:echo-params)}})

(t/deftest invoke-delivers-a-nil-context-without-a-row
  (reset! received-contexts [])
  (let [cfg    (make-cfg (capture-defs))
        params (make-params)]
    (t/is (= params (jobs/invoke (assoc cfg ::jobs/name :echo
                                        ::jobs/params params))))
    (t/is (= [nil] @received-contexts))))

(t/deftest invoke-delivers-an-explicit-context
  (reset! received-contexts [])
  (let [cfg     (make-cfg (capture-defs))
        params  (make-params)
        context (jobs/make-context (mk-row {}))]
    (jobs/invoke (assoc cfg ::jobs/name :echo
                        ::jobs/params params
                        ::jobs/context context))
    (t/is (= [context] @received-contexts))
    (t/testing "an explicit context does not enable heartbeat by itself"
      (t/is (= [] (get-progresss (:id context)))))))

(t/deftest invoke-validates-an-explicit-context
  (let [cfg (make-cfg (capture-defs))]
    (t/testing "a partial context is rejected"
      (t/is (thrown? Exception
                     (jobs/invoke (assoc cfg
                                         ::jobs/name :echo
                                         ::jobs/params (make-params)
                                         ::jobs/context {:id (uuid/next)})))))
    (t/testing "an extra key is rejected: the schema is closed"
      (t/is (thrown? Exception
                     (jobs/invoke (assoc cfg
                                         ::jobs/name :echo
                                         ::jobs/params (make-params)
                                         ::jobs/context
                                         (assoc (jobs/make-context (mk-row {}))
                                                :params {}))))))))

(t/deftest invoke-job-id-is-independent-from-the-context
  (reset! received-contexts [])
  (let [cfg    (make-cfg (capture-defs))
        params (make-params)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params})]
    (jobs/invoke (assoc cfg ::jobs/name :echo
                        ::jobs/params params
                        ::jobs/job-id job-id))
    (t/is (= [nil] @received-contexts)
          "a job-id without a context still delivers a nil context")
    (t/testing "the job-id is what makes the durable writes reach the row"
      (t/is (pos? (jobs/heartbeat cfg :job-id job-id
                                  :progress {:stage :work})))
      (t/is (= [{:stage :work}] (get-progresss job-id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LIFECYCLE EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-running
  "A job already claimed by a worker, ready for a terminal transition."
  [cfg]
  (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    job-id))

(t/deftest lifecycle-writers-require-metrics
  ;; Every one of the six writers must check the metrics instance before
  ;; it writes. Their metric lands inside a `db/after-commit` callback,
  ;; and those swallow exceptions, so without the check a miswired cfg
  ;; persists the row and loses the count with nothing but a log line.
  (let [cfg   (make-cfg (get-job-defs))
        bare  (dissoc cfg ::mtx/metrics)
        count-jobs (fn [] (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job"])))
        state (fn [id] (:status (jobs/get-job cfg id)))]
    (t/testing "the guards exist at all"
      ;; They are `clojure.core/assert` forms, and those are elided when
      ;; *assert* is false at compile time. Without the flag the checks
      ;; are not in the compiled code and every assertion below would
      ;; fail for a reason that has nothing to do with the writers.
      (t/is (true? *assert*)
            "run the suite with PENPOT_FLAGS=\"enable-backend-asserts\""))

    (t/testing "submit never reaches the insert, and writes no row"
      (let [before (count-jobs)]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/submit bare {::jobs/name   :echo
                                                   ::jobs/params (make-params)})))
        (t/is (= before (count-jobs)))))

    (t/testing "claim leaves the pending job pending"
      (let [id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/claim bare id (:scheduled-at (jobs/get-job cfg id)))))
        (t/is (= "new" (state id)))
        (t/is (= [] (get-kinds id)))))

    (t/testing "retry-job leaves the running job alone"
      (let [id (mk-running cfg)]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/retry-job bare id 1 (ct/in-past {:minutes 1})
                                                test-error :backoff)))
        (t/is (= "running" (state id)))
        (t/is (= ["start"] (get-kinds id)))))

    (t/testing "complete leaves the running job alone"
      (let [id (mk-running cfg)]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/complete bare :job-id id :result {:v 1})))
        (t/is (= "running" (state id)))
        (t/is (= ["start"] (get-kinds id)))))

    (t/testing "fail leaves the running job alone"
      (let [id (mk-running cfg)]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/fail bare id test-error)))
        (t/is (= "running" (state id)))
        (t/is (= ["start"] (get-kinds id)))))

    (t/testing "cancel leaves the pending job pending"
      (let [id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
        (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                                (jobs/cancel bare id)))
        (t/is (= "new" (state id)))
        (t/is (= [] (get-kinds id)))))))

(t/deftest lifecycle-writes-own-their-transaction
  (let [cfg (make-cfg (get-job-defs))
        ;; a lifecycle write reports on work that already happened, so it
        ;; never joins the transaction of whoever called it. The row it
        ;; needs is committed beforehand: an independent connection
        ;; cannot see the uncommitted work of the transaction around it.
        rolled-back
        (fn [f]
          (db/tx-run! (assoc cfg ::db/rollback true)
                      (fn [{:keys [::db/conn]}]
                        (f (assoc cfg ::db/conn conn)))))
        state (fn [job-id]
                {:status (:status (jobs/get-job cfg job-id))
                 :kinds  (get-kinds job-id)})]

    (t/testing "claim starts the job and stores the start event"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
            at     (:scheduled-at (jobs/get-job cfg job-id))]
        (t/is (= 1 (rolled-back #(jobs/claim % job-id at))))
        (t/is (= {:status "running" :kinds ["start"]} (state job-id)))))

    (t/testing "retry-job schedules the next attempt and stores the retry event"
      (let [job-id (mk-running cfg)]
        (t/is (= 1 (rolled-back #(jobs/retry-job % job-id 1 (ct/now) test-error :backoff))))
        (t/is (= {:status "retry" :kinds ["start" "retry"]} (state job-id)))))

    (t/testing "complete stores the end event with its result"
      (let [job-id (mk-running cfg)]
        (t/is (= 1 (rolled-back #(jobs/complete % :job-id job-id :result {:v 1}))))
        (t/is (= {:status "completed" :kinds ["start" "end"]} (state job-id)))))

    (t/testing "fail stores the end event with its error"
      (let [job-id (mk-running cfg)]
        (t/is (= 1 (rolled-back #(jobs/fail % job-id test-error))))
        (t/is (= {:status "failed" :kinds ["start" "end"]} (state job-id)))))

    (t/testing "cancel stores the end event"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})]
        (t/is (= 1 (rolled-back #(jobs/cancel % job-id))))
        (t/is (= {:status "cancelled" :kinds ["end"]} (state job-id)))))))

(t/deftest claim-records-a-start-event-with-the-attempt
  (let [cfg (make-cfg (get-job-defs))]
    (t/testing "the first attempt is 1"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})]
        (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
        (t/is (= [{:kind "start" :payload {:attempt 1}}] (get-events job-id)))))

    (t/testing "a retried job claims attempt retry-num + 1"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})]
        (th/db-update! :job {:retry-num 2} {:id job-id})
        (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
        (t/is (= [{:kind "start" :payload {:attempt 3}}] (get-events job-id)))))))

(t/deftest claim-that-affects-no-row-writes-no-event
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/is (= 0 (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))))
    (t/testing "only the first claim left a start event"
      (t/is (= ["start"] (get-kinds job-id))))))

(t/deftest retry-job-records-a-retry-event-per-reason
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/testing "a backoff increments the counter and reports the new attempt"
      (jobs/retry-job cfg job-id 1 (ct/now) test-error :backoff)
      (t/is (= {:attempt 2 :reason "backoff"}
               (:payload (last (get-events job-id))))))

    (t/testing "a noop keeps the counter and re-reports the same attempt"
      (jobs/retry-job cfg job-id 1 (ct/now) test-error :noop)
      (t/is (= {:attempt 2 :reason "noop"}
               (:payload (last (get-events job-id))))))

    (t/testing "the counter on the row is the one we asked for"
      (t/is (= 1 (:retry-num (jobs/get-job cfg job-id)))))

    (t/testing "a terminal job gets no retry and no event"
      (th/db-update! :job {:status "failed"} {:id job-id})
      (t/is (= 0 (jobs/retry-job cfg job-id 2 (ct/now) test-error :backoff)))
      (t/is (= ["start" "retry" "retry"] (get-kinds job-id))))))

(t/deftest complete-records-an-end-event
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/is (= 1 (jobs/complete cfg :job-id job-id :result {:value 1})))
    (t/is (= ["start" "end"] (get-kinds job-id)))
    (t/is (= ["completed"] (get-outcomes job-id)))
    (t/testing "a terminal job gets no second end event"
      (t/is (= 0 (jobs/complete cfg :job-id job-id :result {:value 2})))
      (t/is (= ["completed"] (get-outcomes job-id))))))

(t/deftest fail-records-an-end-event-and-a-structured-error
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/is (= 1 (jobs/fail cfg job-id test-error)))
    (t/is (= ["failed"] (get-outcomes job-id)))
    (t/testing "the error decodes back to the map we passed"
      (t/is (= test-error (jobs/decode-job-error (:error (jobs/get-job cfg job-id))))))
    (t/testing "the event carries no error, only the outcome"
      (t/is (= {:outcome "failed"} (:payload (last (get-events job-id))))))
    (t/testing "a terminal job gets no second end event"
      (t/is (= 0 (jobs/fail cfg job-id test-error)))
      (t/is (= ["failed"] (get-outcomes job-id))))))

(t/deftest cancel-records-an-end-event
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/is (= 1 (jobs/cancel cfg job-id)))
    (t/is (= ["cancelled"] (get-outcomes job-id)))
    (t/testing "a terminal job gets no second end event"
      (t/is (= 0 (jobs/cancel cfg job-id)))
      (t/is (= ["cancelled"] (get-outcomes job-id))))))

(t/deftest aborted-job-is-terminal-for-all-writers
  "An aborted job (system-side terminal set by the orphan sweep) cannot be
  moved by any lifecycle writer: first-terminal-wins, with no event and no
  second transition."
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)
        at     (:scheduled-at (jobs/get-job cfg job-id))]
    (th/db-update! :job {:status "aborted"
                         :error  (db/json jobs/orphan-error)}
                   {:id job-id})
    (t/is (zero? (jobs/claim cfg job-id at)))
    (t/is (zero? (jobs/retry-job cfg job-id 1 (ct/now) test-error :backoff)))
    (t/is (zero? (jobs/complete cfg :job-id job-id :result {:v 1})))
    (t/is (zero? (jobs/fail cfg job-id test-error)))
    (t/is (zero? (jobs/cancel cfg job-id)))
    (t/is (= "aborted" (:status (jobs/get-job cfg job-id))))
    (t/is (= ["start"] (get-kinds job-id))
          "only the claim start event, no end event from any writer")))

(defn- with-failing-event
  "Run `f` with the event insert forced to fail, the way a database that
  rejects the row would. Everything the transition writes must go back
  with the transaction that carried it."
  [f]
  (with-redefs [jobs/insert-event (fn [& _]
                                    (throw (ex-info "event insert failed" {})))]
    (f)))

(defn- job-columns
  "Every column a transition can move, decoded, so a before/after
  comparison is a real one."
  [cfg job-id]
  (let [row (jobs/get-job cfg job-id)]
    (cond-> (select-keys row [:status :modified-at :completed-at :retry-num
                              :scheduled-at :resource-id :result :error])
      (:error row)  (update :error db/decode-json-pgobject)
      (:result row) (update :result db/decode-json-pgobject))))

(t/deftest a-failing-event-write-rolls-back-every-writer
  (let [cfg (make-cfg (get-job-defs))
        unchanged
        (fn [job-id before]
          (t/is (= before (job-columns cfg job-id))
                "no column the transition touches survived")
          (t/is (not-any? #(= "end" %) (get-kinds job-id))
                "and no event of the failed transition was left behind"))]

    (t/testing "claim"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
            at     (:scheduled-at (jobs/get-job cfg job-id))
            before (job-columns cfg job-id)]
        (with-failing-event #(t/is (thrown? Exception (jobs/claim cfg job-id at))))
        (unchanged job-id before)
        (t/is (= [] (get-kinds job-id)))))

    (t/testing "retry-job"
      (let [job-id (mk-running cfg)
            before (job-columns cfg job-id)]
        (with-failing-event
          #(t/is (thrown? Exception
                          (jobs/retry-job cfg job-id 1 (ct/in-past {:minutes 1})
                                          test-error :backoff))))
        (unchanged job-id before)
        (t/is (= ["start"] (get-kinds job-id)))))

    (t/testing "complete"
      (let [job-id (mk-running cfg)
            before (job-columns cfg job-id)]
        (with-failing-event
          #(t/is (thrown? Exception
                          (jobs/complete cfg :job-id job-id :result {:v 1}))))
        (unchanged job-id before)
        (t/is (= ["start"] (get-kinds job-id)))))

    (t/testing "fail"
      (let [job-id (mk-running cfg)
            before (job-columns cfg job-id)]
        (with-failing-event #(t/is (thrown? Exception (jobs/fail cfg job-id test-error))))
        (unchanged job-id before)
        (t/is (= ["start"] (get-kinds job-id)))))

    (t/testing "cancel"
      (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
            before (job-columns cfg job-id)]
        (with-failing-event #(t/is (thrown? Exception (jobs/cancel cfg job-id))))
        (unchanged job-id before)
        (t/is (= [] (get-kinds job-id)))))

    (t/testing "a resource a failed completion associated is undone with it"
      (let [job-id      (mk-running cfg)
            resource-id (uuid/next)]
        (th/db-insert! :storage-object {:id resource-id :backend "test"})
        (with-failing-event
          #(t/is (thrown? Exception
                          (jobs/complete cfg :job-id job-id :result {:v 1}
                                         :resource-id resource-id))))
        (let [row (jobs/get-job cfg job-id)]
          (t/is (nil? (:resource-id row)))
          (t/is (= "running" (:status row))))))))

(t/deftest insert-event-requires-a-transaction
  ;; The event insert is only valid inside the transaction that owns the
  ;; job row: outside one the assert fires instead of writing. Like every
  ;; assert-based guard, this only holds with the backend asserts flag.
  (t/is (true? *assert*)
        "run the suite with PENPOT_FLAGS=\"enable-backend-asserts\"")
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/testing "a pool cfg with no transaction raises"
      (t/is (thrown? AssertionError
                     (@#'jobs/insert-event cfg job-id "progress" {:stage :half}))))
    (t/testing "a bare connection with no transaction raises too"
      (db/run! cfg (fn [conn-cfg]
                     (t/is (thrown? AssertionError
                                    (@#'jobs/insert-event conn-cfg job-id "progress"
                                                          {:stage :half}))))))))

(t/deftest complete-associates-a-resource-id-once
  (let [cfg         (make-cfg (get-job-defs))
        resource-id (uuid/next)]
    (th/db-insert! :storage-object {:id resource-id :backend "test"})

    (t/testing "a job without resource gets one at completion"
      (let [job-id (mk-running cfg)]
        (t/is (= 1 (jobs/complete cfg :job-id job-id
                                  :result {:v 1}
                                  :resource-id resource-id)))
        (t/is (= resource-id (:resource-id (jobs/get-job cfg job-id))))))

    (t/testing "completing without a resource leaves the column untouched"
      (let [job-id (mk-running cfg)]
        (t/is (= 1 (jobs/complete cfg :job-id job-id :result {:v 1})))
        (t/is (nil? (:resource-id (jobs/get-job cfg job-id))))))

    (t/testing "a job that already has a resource refuses a second one"
      (let [job-id (mk-running cfg)]
        ;; a job that already carries a resource, as submit left it
        (th/db-update! :job {:resource-id resource-id} {:id job-id})
        (t/is (thrown-with-msg? Exception #"already has a resource"
                                (jobs/complete cfg
                                               :job-id job-id
                                               :result {:v 1}
                                               :resource-id (uuid/next))))
        (t/testing "and the job is still running"
          (t/is (= "running" (:status (jobs/get-job cfg job-id)))))))

    (t/testing "a resource that is not a uuid is rejected before any write"
      (let [job-id (mk-running cfg)]
        (t/is (thrown-with-msg? Exception #"must be a uuid"
                                (jobs/complete cfg
                                               :job-id job-id
                                               :result {:v 1}
                                               :resource-id "nope")))
        (t/is (= "running" (:status (jobs/get-job cfg job-id))))))))

(t/deftest job-error-schema-is-shared-and-validated
  (t/testing "type and code are keywords so they decode back from JSON"
    (t/is (= {:type :internal :code :orphan :hint "gone"}
             (jobs/check-job-error {:type :internal :code :orphan :hint "gone"}))))

  (t/testing "a missing key is rejected"
    (t/is (thrown? Exception (jobs/check-job-error {:type :internal :code :orphan})))
    (t/is (thrown? Exception (jobs/check-job-error {:type :internal
                                                    :code    "orphan"
                                                    :hint    "gone"}))))

  (t/testing "extra details are allowed"
    (t/is (= {:type :internal :code :orphan :hint "gone" :attempt 3}
             (jobs/check-job-error {:type    :internal
                                    :code    :orphan
                                    :hint    "gone"
                                    :attempt 3}))))

  (t/testing "fail validates before writing"
    (let [cfg    (make-cfg (get-job-defs))
          job-id (mk-running cfg)]
      (t/is (thrown? Exception (jobs/fail cfg job-id {:code :orphan})))
      (t/is (= "running" (:status (jobs/get-job cfg job-id))))
      (t/is (= ["start"] (get-kinds job-id)))))

  (t/testing "the orphan error is a valid job error"
    (t/is (= jobs/orphan-error (jobs/check-job-error jobs/orphan-error)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONTENTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The safety of the writers rests on two things: a conditional UPDATE
;; that only the first caller can win, and a row lock the loser waits
;; on. Both are only meaningful when two callers arrive at once, so the
;; tests below put real threads in front of them.

(defn- race
  "Run every thunk on a thread of its own and return what each returned,
  in the order they were given. They all wait on the same gate, so they
  contend for whatever they share instead of running one after another."
  [& thunks]
  (let [gate    (promise)
        results (mapv (fn [f]
                        (future
                          @gate
                          (f)))
                      thunks)]
    (deliver gate true)
    (mapv deref results)))

(t/deftest the-first-terminal-transition-wins
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/testing "a completion and a failure of the same job, at the same time"
      (let [[completed failed] (race #(jobs/complete cfg :job-id job-id :result {:v 1})
                                     #(jobs/fail cfg job-id test-error))]
        (t/is (= #{0 1} (set [completed failed]))
              "one transition is applied and the other affects no row"))

      (t/testing "and the durable history agrees with the row that won"
        (let [kinds    (get-kinds job-id)
              outcomes (get-outcomes job-id)
              row      (jobs/get-job cfg job-id)]
          (t/is (= ["start" "end"] kinds)
                "exactly one end event, written by whoever won")
          (t/is (= 1 (count outcomes)))
          (t/is (= (first outcomes) (:status row))
                "the stored outcome is the status of the row"))))))

(t/deftest the-first-claim-wins
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})
        at     (:scheduled-at (jobs/get-job cfg job-id))]
    (t/testing "two workers claim the same advertised job at the same time"
      (let [[a b] (race #(jobs/claim cfg job-id at)
                        #(jobs/claim cfg job-id at))]
        (t/is (= [0 1] (sort [a b])))
        (t/is (= "running" (:status (jobs/get-job cfg job-id))))
        (t/is (= ["start"] (get-kinds job-id))
              "a job is started once however many workers ask for it")))))

(t/deftest a-report-never-lands-on-a-terminal-job
  (let [cfg    (make-cfg (get-job-defs))
        job-id (mk-running cfg)]
    (t/testing "a progress report and a completion of the same job, at once"
      ;; the report either lands before the end or loses the race and gets
      ;; the interrupt: a beat on a job that is no longer active raises
      (let [report (fn []
                     (if (some? (raised #(jobs/heartbeat cfg :job-id job-id
                                                         :progress {:stage :work}
                                                         ::jobs/force? true)))
                       :interrupted
                       :reported))
            [outcome completed] (race report #(jobs/complete cfg :job-id job-id :result {:v 1}))]
        (t/is (contains? #{:interrupted :reported} outcome)
              "the report lands or gets the interrupt, never anything else")
        (t/is (= 1 completed) "the completion always wins, it owns the row")
        (t/testing "the report either lost the race or was stored before the end"
          (let [kinds (get-kinds job-id)]
            (t/is (= "start" (first kinds)))
            (t/is (= "end" (last kinds))
                  "the end is always the last event, so no report came after it")
            (t/is (<= 2 (count kinds) 3)
                  "at most the one report that made it in before the end"))))
      (t/testing "a job that ended is not resurrected by a late report"
        (let [before (get-kinds job-id)
              cause  (raised #(jobs/heartbeat cfg :job-id job-id
                                              :progress {:stage :work}
                                              ::jobs/force? true))]
          (t/is (= :interrupt (th/ex-type cause)))
          (t/is (= before (get-kinds job-id)) "the late report stored nothing"))))))

(t/deftest progress-event-reaches-the-cfg-of-its-own-callback
  "The event helper needs the msgbus and the metrics from the cfg, and it
  must find them on the map that `db/tx-run!` hands to the callback: the
  writers pass the whole cfg, not a rebuilt one. A cfg missing either
  dependency cannot produce a published event, so the publication is
  what proves the cfg arrived intact."
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        messages   (atom [])
        job-cfg    (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id     (jobs/submit job-cfg {::jobs/name       :echo
                                         ::jobs/params     (make-params)
                                         ::jobs/profile-id profile-id})]
    (t/is (pos? (jobs/heartbeat job-cfg :job-id job-id
                                :progress {:stage :work})))
    (t/testing "the event was stored"
      (t/is (= [{:stage :work}] (get-progresss job-id))))
    (t/testing "and the msgbus on that cfg was reachable from the callback"
      (t/is (= 1 (count @messages)))
      (t/is (= "progress" (:kind (:message (first @messages))))))))
