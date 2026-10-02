;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-error-reports-test
  "Tests for the superuser-guarded error-reports commands."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- insert-report!
  "Insert a raw error report row. content is a plain map (will be tjson-encoded)."
  [{:keys [id source content created-at]
    :or {created-at (ct/now)}}]
  (th/db-insert! :server-error-report
                 {:id (or id (uuid/next))
                  :source source
                  :created-at created-at
                  :content (db/tjson content)}))

(defn- raw-method
  "The raw [f mdata] behind `cmd-name` in the admin namespace."
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.commands.admin)))

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

(defn- caught-code
  "Run `thunk` and return the error code it raised, or `::no-throw`."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest listed-session-lists-and-reads
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= {:items []} (run "get-admin-error-reports" {})))))

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-admin-error-reports" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "get-admin-error-report" {:id (uuid/next)}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-admin-error-reports" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [profile (th/create-profile* 1)]
    (t/is (= {:items []}
             (call #{} :token (:id profile) #{"superuser"}
                   "get-admin-error-reports" {})))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-admin-error-reports" {})))))

(t/deftest invalid-params-still-validated-for-listed-caller
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= :params-validation
             (caught-code #(run "get-admin-error-reports" {:limit 500}))))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-summaries
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (insert-report! {:source 3 :content {:hint "boom" :tenant "devenv" :version "2.20"}})
    (let [out (run "get-admin-error-reports" {})]
      (t/is (= 1 (count (:items out))))
      (t/is (= "logging" (:source (first (:items out)))))
      (t/is (= "boom" (:hint (first (:items out))))))))

(t/deftest list-filters-by-source
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (insert-report! {:source 3 :content {:hint "backend"}})
    (insert-report! {:source 4 :content {:hint "audit"}})
    (let [out (run "get-admin-error-reports" {:source "audit-log"})]
      (t/is (= 1 (count (:items out))))
      (t/is (= "audit-log" (:source (first (:items out))))))))

(t/deftest list-paginates
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (insert-report! {:source 3 :content {:hint "one"}})
    (insert-report! {:source 3 :content {:hint "two"}})
    (insert-report! {:source 3 :content {:hint "three"}})
    (let [page1 (run "get-admin-error-reports" {:limit 2})]
      (t/is (= 2 (count (:items page1))))
      (t/is (some? (:next-since page1)))
      (t/is (some? (:next-id page1)))
      (let [page2 (run "get-admin-error-reports" {:limit 2
                                                  :since (:next-since page1)
                                                  :since-id (:next-id page1)})]
        (t/is (= 1 (count (:items page2))))
        (t/is (nil? (:next-since page2)))))))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-returns-full-report
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)
        id      (uuid/next)]
    (insert-report! {:id id :source 5
                     :content {:hint "rlimit hit"
                               :report "stacktrace here"
                               :context "extra context"}})
    (let [out (run "get-admin-error-report" {:id id})]
      (t/is (= id (:id out)))
      (t/is (= "rlimit" (:source out)))
      (t/is (= "rlimit hit" (:hint out)))
      (t/is (= "stacktrace here" (:report out)))
      (t/is (= "extra context" (:context out))))))

(t/deftest detail-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= :report-not-found
             (caught-code #(run "get-admin-error-report" {:id (uuid/next)}))))))
