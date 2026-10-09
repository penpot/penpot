;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-jobs-test
  "Tests for the superuser-guarded job inspection commands."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- raw-method
  "The raw [f mdata] behind `cmd-name` in the admin namespace."
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.job)))

(defn- call
  "Invoke an admin command through the real wrapper chain with a
  hand-built config.

  The chain closes over its cfg at startup, so the superuser set is
  injected per call here instead of going through `th/command!`.
  Qualified keys stay in `params` as server context; the rest goes
  into the request, where params validation reads it from."
  [superusers auth-type profile-id token-perms cmd params]
  (let [[f mdata]   (raw-method cmd)
        cfg         (assoc th/*system* ::auth/superusers superusers)
        request     (assoc (th/make-dummy-request)
                           :params (d/without-qualified params))
        server      (with-meta params {::rpc/request-at (ct/now)
                                       ::http/request request})
        wrapped     (#'rpc/wrap cfg f mdata)]
    (wrapped cfg (assoc server
                        ::rpc/profile-id profile-id
                        ::rpc/auth-type auth-type
                        ::rpc/token-perms token-perms))))

(defn- as-session
  [superusers profile]
  (fn [cmd params]
    (call superusers :session (:id profile) #{} cmd params)))

(defn- as-superuser
  [profile]
  (as-session #{(:id profile)} profile))

(defn- caught-code
  "Run `thunk` and return the error code it raised, or `::no-throw`."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- mk-job
  "Insert a `job` row straight into the table, newest-first friendly:
  `created-offset` shifts `created-at` back by that many seconds so
  pagination tests own their order without sleeping."
  [{:keys [name queue status profile-id tenant created-offset]
    :or   {name "sendmail"
           queue "default"
           status "new"
           tenant (cf/get :tenant)
           created-offset 0}}]
  (let [id  (uuid/next)
        now (ct/plus (ct/now) (ct/duration {:seconds (- created-offset)}))]
    (th/db-insert! :job {:id           id
                         :name         name
                         :tenant       tenant
                         :queue        queue
                         :params       (db/json {:marker (str id)})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       status
                         :profile-id   profile-id
                         :scheduled-at now
                         :created-at   now
                         :modified-at  now})
    id))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-system-job-has-no-owner
  (let [admin (th/create-profile* 1)
        id    (mk-job {:name "objects-gc" :status "completed"})
        run   (as-superuser admin)
        out   (run "get-job" {:id id})]
    (t/is (= id (:id out)))
    (t/is (= "objects-gc" (:name out)))
    (t/is (= "system" (:kind out)))
    (t/is (not (contains? out :owner-email)))
    (t/is (string? (:params-pretty out)))
    (t/is (not (contains? out :params))
          "the raw payload stays out: only the bounded pretty text travels")))

(t/deftest detail-user-job-resolves-owner
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        id    (mk-job {:profile-id (:id user)})
        run   (as-superuser admin)
        out   (run "get-job" {:id id})]
    (t/is (= "user" (:kind out)))
    (t/is (= (:id user) (:profile-id out)))
    (t/is (= (:email user) (:owner-email out)))
    (t/is (= (:fullname user) (:owner-fullname out)))))

(t/deftest detail-returns-events-oldest-first
  (let [admin (th/create-profile* 1)
        id    (mk-job {:status "completed"})
        _     (doseq [[kind payload] [["start" {}]
                                      ["progress" {:current 1 :total 2}]
                                      ["retry" {:attempt 2 :reason "backoff"}]
                                      ["end" {:outcome "completed"}]]]
                (th/db-insert! :job-event {:job-id id
                                           :kind kind
                                           :payload (db/json payload)}))
        run   (as-superuser admin)
        out   (run "get-job" {:id id})]
    (t/is (= ["start" "progress" "retry" "end"]
             (mapv :kind (:events out))))
    (t/is (= {:outcome "completed"} (:payload (last (:events out)))))
    (t/is (every? some? (map :created-at (:events out))))))

(t/deftest detail-bounds-params-pretty
  (let [admin (th/create-profile* 1)
        deep  (reduce (fn [acc _] {:nest acc}) {:leaf (range 100)} (range 30))
        id    (mk-job {})
        _     (th/db-update! :job {:params (db/json deep)} {:id id})
        run   (as-superuser admin)
        out   (run "get-job" {:id id})]
    (t/is (<= (count (:params-pretty out)) 4100))
    (t/is (not (str/includes? (:params-pretty out) "99"))
          "depth and length bounds cut the payload before the leaf")))

(t/deftest detail-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :job-not-found
             (caught-code #(run "get-job" {:id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Cancel
;; ----------------------------------------------------------------

(defn- job-status
  "Raw status plus the count of `end` events, to prove the
  transition wrote exactly its own history."
  [id]
  {:status (:status (th/db-get :job {:id id}))
   :ends   (:count (th/db-exec-one!
                    ["SELECT count(*) AS count FROM job_event
                      WHERE job_id = ? AND kind = 'end'" id]))})

(t/deftest cancel-moves-live-jobs-to-cancelled
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (doseq [status ["new" "scheduled" "retry" "running"]]
      (let [id  (mk-job {:status status})
            out (run "cancel-job" {:id id})]
        (t/is (= true (:cancelled out)) (str "cancels " status))
        (t/is (= "cancelled" (:status (job-status id))))
        (t/is (= 1 (:ends (job-status id))) "exactly one end event")))))

(t/deftest cancel-leaves-terminal-jobs-untouched
  (let [admin (th/create-profile* 1)
        run   (as-superuser admin)]
    (doseq [status ["completed" "failed" "cancelled" "aborted"]]
      (let [id  (mk-job {:status status})
            out (run "cancel-job" {:id id})]
        (t/is (= false (:cancelled out)) (str "refuses " status))
        (t/is (= status (:status (job-status id))))
        (t/is (= 0 (:ends (job-status id))) "writes no event")))))

(t/deftest cancel-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :job-not-found
             (caught-code #(run "cancel-job" {:id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)
        missing (uuid/next)]
    (t/is (= :superuser-required
             (caught-code #(run "get-jobs" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-job" {:id missing}))))
    (t/is (= :superuser-required
             (caught-code #(run "cancel-job" {:id missing}))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-jobs" {})))))

(t/deftest invalid-params-rejected-for-listed-caller
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "get-jobs" {:limit 500}))))
    (t/is (= :params-validation
             (caught-code #(run "get-jobs" {:status "exploding"}))))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-newest-first
  (let [admin (th/create-profile* 1)
        old   (mk-job {:created-offset 30 :status "completed"})
        new   (mk-job {:created-offset 0 :status "completed"})
        run   (as-superuser admin)
        out   (run "get-jobs" {})
        item  (first (:items out))]
    (t/is (= [new old] (mapv :id (:items out))))
    (t/is (= "sendmail" (:name item)))
    (t/is (= "default" (:queue item)))
    (t/is (= "completed" (:status item)))
    (t/is (= "system" (:kind item)))
    (t/is (= 0 (:retry-num item)))
    (t/is (= 3 (:max-retries item)))
    (t/is (some? (:created-at item)))
    (t/is (some? (:scheduled-at item)))
    (t/is (not (contains? item :params))
          "the list stays light: no business payload")))

(t/deftest list-marks-user-jobs
  (let [admin (th/create-profile* 1)
        user  (th/create-profile* 2)
        owned (mk-job {:profile-id (:id user)})
        run   (as-superuser admin)
        out   (run "get-jobs" {:id owned})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= "user" (:kind item)))
    (t/is (= (:id user) (:profile-id item)))))

(t/deftest list-filters-by-name-and-status
  (let [admin (th/create-profile* 1)
        failed-mail (mk-job {:name "sendmail" :status "failed"})
        _ok-mail    (mk-job {:name "sendmail" :status "completed"})
        _failed-gc  (mk-job {:name "objects-gc" :status "failed"})
        run   (as-superuser admin)]
    (let [out (run "get-jobs" {:name "sendmail"})]
      (t/is (= 2 (count (:items out))))
      (t/is (every? #(= "sendmail" (:name %)) (:items out))))
    (let [out (run "get-jobs" {:status "failed"})]
      (t/is (= 2 (count (:items out))))
      (t/is (every? #(= "failed" (:status %)) (:items out))))
    (let [out (run "get-jobs" {:name "sendmail" :status "failed"})]
      (t/is (= [failed-mail] (mapv :id (:items out)))))))

(t/deftest list-looks-up-by-id
  (let [admin (th/create-profile* 1)
        one   (mk-job {})
        _two  (mk-job {})
        run   (as-superuser admin)
        out   (run "get-jobs" {:id one})]
    (t/is (= [one] (mapv :id (:items out))))))

(t/deftest list-hides-other-tenants
  (let [admin   (th/create-profile* 1)
        foreign (mk-job {:tenant "other-tenant"})
        _mine   (mk-job {})
        run     (as-superuser admin)
        out     (run "get-jobs" {})]
    (t/is (= 1 (count (:items out))))
    (t/is (not (contains? (set (mapv :id (:items out))) foreign)))))

(t/deftest list-paginates
  (let [admin (th/create-profile* 1)
        ids   (mapv #(mk-job {:created-offset (* 10 %)}) (range 5))
        run   (as-superuser admin)
        first-page  (run "get-jobs" {:limit 2})
        _           (t/is (= (take 2 ids) (mapv :id (:items first-page))))
        second-page (run "get-jobs" {:limit 2
                                     :since (:next-since first-page)
                                     :since-id (:next-id first-page)})]
    (t/is (= (take 2 (drop 2 ids)) (mapv :id (:items second-page))))
    (t/is (some? (:next-since second-page)))))
