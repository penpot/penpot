;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-misc-test
  "Tests for the superuser-guarded misc commands (virtual clock)."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.setup.clock :as clock]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- raw-method
  "The raw [f mdata] behind `cmd-name` in the admin misc namespace."
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.misc)))

(defn- call
  "Invoke an admin command through the real wrapper chain with a
  hand-built config. See rpc-admin-error-reports-test for why the
  superuser set is injected per call."
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
;; get-virtual-clock
;; ----------------------------------------------------------------

(t/deftest listed-session-reads-clock
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)
        result  (run "get-virtual-clock" {})]
    (t/is (nil? (:offset-millis result)))
    (t/is (= "system" (:clock result)))
    (t/is (some? (:now result)))))

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-virtual-clock" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "set-virtual-clock" {:offset "1h"}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "get-virtual-clock" {}))))))

(t/deftest token-with-granted-superuser-passes
  (let [profile (th/create-profile* 1)]
    (t/is (= "system"
             (:clock (call #{} :token (:id profile) #{"superuser"}
                           "get-virtual-clock" {}))))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "get-virtual-clock" {})))))

;; ----------------------------------------------------------------
;; set-virtual-clock
;; ----------------------------------------------------------------

(t/deftest listed-session-sets-and-reads-back
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (try
      (let [result (run "set-virtual-clock" {:offset "1h"})]
        (t/is (= 3600000 (:offset-millis result)))
        (t/is (= "offset" (:clock result)))
        (t/is (= 3600000 (:offset-millis (run "get-virtual-clock" {})))))
      (finally
        (clock/assign-offset (:id profile) nil)))))

(t/deftest reset-clears-offset
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (try
      (run "set-virtual-clock" {:offset "1h"})
      (let [result (run "set-virtual-clock" {:reset true})]
        (t/is (nil? (:offset-millis result)))
        (t/is (= "system" (:clock result))))
      (finally
        (clock/assign-offset (:id profile) nil)))))

(t/deftest zero-duration-clears-offset
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (try
      (run "set-virtual-clock" {:offset "1h"})
      (let [result (run "set-virtual-clock" {:offset "0s"})]
        (t/is (nil? (:offset-millis result)))
        (t/is (= "system" (:clock result))))
      (finally
        (clock/assign-offset (:id profile) nil)))))

(t/deftest invalid-offset-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= :params-validation
             (caught-code #(run "set-virtual-clock"
                                {:offset "not-a-duration"}))))))

(t/deftest missing-offset-and-reset-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= :missing-arguments
             (caught-code #(run "set-virtual-clock" {}))))))
