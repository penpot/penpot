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
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)

(defn- test-fixture [next]
  (th/database-reset next))

(t/use-fixtures :each test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB-DEF (plain handler + precompiled init-key)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn echo-handler
  "Plain handler function, importable and testable without integrant."
  [cfg params]
  (when (::jobs/job-id cfg)
    (jobs/heartbeat! cfg)
    (jobs/progress! cfg {:step "half"}))
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
   ::jobs/handler   (partial echo-handler cfg)
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
  {::jobs/defs defs
   ::db/pool   th/*pool*})

(defn- make-params
  []
  {:text      "hello"
   :object    :snapshot
   :deleted-at (ct/now)
   :id        (uuid/next)
   :file-id   (uuid/next)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest submit-validates-params-with-job-schema
  (let [cfg (make-cfg (get-job-defs))]
    (t/is (thrown-with-msg? Exception #"check error"
                            (jobs/submit! cfg {::jobs/name   :echo
                                               ::jobs/params {:text "hello"
                                                              :object :snapshot
                                                              :deleted-at (ct/now)
                                                              :id (uuid/next)
                                                              :file-id "not-an-uuid"}}))))

  (t/testing "missing job definition raises a clear error"
    (let [cfg (make-cfg (get-job-defs))]
      (t/is (thrown-with-msg? Exception #"no job definition"
                              (jobs/submit! cfg {::jobs/name   :unknown
                                                 ::jobs/params {}}))))))

(t/deftest submit-persists-row-with-json-props
  (let [cfg   (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/queue  :test
                                  ::jobs/delay  1000
                                  ::jobs/priority 200
                                  ::jobs/label  "test-label"
                                  ::jobs/max-retries 5})
        row    (jobs/get-job cfg job-id)]

    (t/is (uuid? job-id))
    (t/is (= "echo" (:name row)))
    (t/is (= (str (cf/get :tenant) ":" "test") (:queue row)))
    (t/is (= "test-label" (:label row)))
    (t/is (= 200 (:priority row)))
    (t/is (= 5 (:max-retries row)))
    (t/is (= "new" (:status row)))

    (t/testing "scheduled_at respects the submitted delay"
      (let [delay (ct/duration {:seconds 1})]
        (t/is (pos? (- (inst-ms (:scheduled-at row)) (inst-ms (ct/now))))
              (str "scheduled-at should be in the future: "
                   (pr-str (:scheduled-at row))))))

    (t/testing "props are stored as plain JSON (no transit tags) and decoded"
      (let [props (:props row)]
        (t/is (map? props))
        (t/is (nil? (some-> (th/db-exec-one! ["SELECT props::text FROM job WHERE id = ?"
                                              job-id])
                            :props
                            (str/index-of "~#"))))

        (t/testing "round-trip: decoded values have proper clojure types"
          (let [decoded (jobs/decode-params (jobs/get-job-def (get cfg ::jobs/defs) :echo)
                                            (:props row))]
            (t/is (keyword? (:object decoded)))
            (t/is (= :snapshot (:object decoded)))
            (t/is (uuid? (:id decoded)))
            (t/is (uuid? (:file-id decoded)))
            (t/is (ct/inst? (:deleted-at decoded)))))))))

(t/deftest submit-dedupe-replaces-not-due-new-rows
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-label"
        id1    (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/label  label
                                  ::jobs/dedupe true
                                  ::jobs/delay  10000})
        id2    (jobs/submit! cfg {::jobs/name   :echo
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
        id1    (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/label  label})
        _      (th/db-update! :job {:status "running"} {:id id1})
        id2    (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/label  label
                                  ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label])))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-due-label"
        id1    (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/label  label})
        _      (th/db-update! :job {:scheduled-at (ct/minus (ct/now)
                                                            (ct/duration {:seconds 5}))}
                              {:id id1})
        id2    (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params
                                  ::jobs/label  label
                                  ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label]))))))

(t/deftest plain-handler-is-testable-without-integrant
  (let [params (make-params)]
    (t/is (= params (echo-handler {} params))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params params})]
    (t/testing "handler receives cfg with job-id context for heartbeats"
      (t/is (= params (echo-handler (assoc cfg ::jobs/job-id job-id) params))))))

(t/deftest heartbeat-respects-throttle
  (let [cfg   (make-cfg (get-job-defs))
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params (make-params)})]

    (t/testing "first heartbeat writes"
      (jobs/heartbeat! cfg job-id)
      (let [row (jobs/get-job cfg job-id)]
        (t/is (> (inst-ms (:modified-at row)) (inst-ms (:created-at row))))))

    (t/testing "immediate second heartbeat does not write (throttled)"
      (let [row1 (jobs/get-job cfg job-id)
            _    (jobs/heartbeat! cfg job-id)
            row2 (jobs/get-job cfg job-id)]
        (t/is (= (inst-ms (:modified-at row1))
                 (inst-ms (:modified-at row2))))))))

(t/deftest progress-respects-throttle-and-skips-terminal-states
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params (make-params)})]

    (t/testing "first progress write persists the payload"
      (jobs/progress! cfg job-id {:step 1})
      (let [row (jobs/get-job cfg job-id)]
        (t/is (= {:step 1} (:progress row)))))

    (t/testing "immediate second progress write is throttled"
      (jobs/progress! cfg job-id {:step 2})
      (t/is (= {:step 1} (:progress (jobs/get-job cfg job-id))))

      (t/testing "after the throttle window elapses it writes again"
        (swap! @#'jobs/progresses
               (fn [m]
                 (update-in m [job-id]
                            #(ct/minus %
                                       (ct/duration {:millis 500})))))
        (jobs/progress! cfg job-id {:step 3})
        (t/is (= {:step 3} (:progress (jobs/get-job cfg job-id)))))))

  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params (make-params)})]
    (t/testing "terminal states are never updated by progress"
      (th/db-update! :job {:status "completed"} {:id job-id})
      (swap! @#'jobs/progresses dissoc job-id)
      (jobs/progress! cfg job-id {:step 9})
      (t/is (nil? (:progress (jobs/get-job cfg job-id)))))))

(t/deftest cancel-skips-running-and-terminal-jobs
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params (make-params)})]

    (t/testing "pending job can be cancelled"
      (t/is (= 1 (jobs/cancel! cfg job-id)))
      (t/is (= "cancelled" (:status (jobs/get-job cfg job-id))))

      (t/testing "already cancelled job is not affected again"
        (t/is (zero? (jobs/cancel! cfg job-id))))))

  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit! cfg {::jobs/name   :echo
                                  ::jobs/params (make-params)})]
    (t/testing "running job cannot be cancelled"
      (th/db-update! :job {:status "running"} {:id job-id})
      (t/is (zero? (jobs/cancel! cfg job-id)))
      (t/is (= "running" (:status (jobs/get-job cfg job-id)))))))

(t/deftest get-user-status-maps-internal-statuses
  (t/are [status expected] (= expected (jobs/get-user-status status))
    "new"       "pending"
    "scheduled" "pending"
    "retry"     "pending"
    :new        "pending"
    "running"   "running"
    "completed" "completed"
    "failed"    "failed"
    "cancelled" "failed"))

(t/deftest job-defs-registry-validates-definitions
  (t/testing "valid job-def map passes the assert"
    (t/is (some? (get-job-defs))))

  (t/testing "registry name mismatch is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name      :other
                                           ::jobs/schema    schema:echo-params
                                           ::jobs/handler   echo-handler
                                           ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
                                           ::jobs/validator (sm/validator schema:echo-params)}}}))))

  (t/testing "missing decoder/validator is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name    :echo
                                           ::jobs/schema  schema:echo-params
                                           ::jobs/handler echo-handler}}})))))
