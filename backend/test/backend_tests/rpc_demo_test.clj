;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-demo-test
  (:require
   [app.auth :as auth]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.rpc.commands.profile :as profile]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; Capture the real verifier before the shared test fixture replaces it.
(def verify-password* auth/verify-password)

(t/deftest weak-password-hash-verifies
  (let [password "DemoPassword123!"
        hashed   (auth/derive-password-weak password)]
    (t/is (:valid (verify-password* password hashed)))))

(t/deftest create-demo-profile-uses-unique-uuid-email
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [first-result  (th/command! {::th/type :create-demo-profile})
          second-result (th/command! {::th/type :create-demo-profile})
          first-profile  (:result first-result)
          second-profile (:result second-result)]
      (t/is (nil? (:error first-result)))
      (t/is (nil? (:error second-result)))
      (t/is (re-matches #"demo-[0-9a-fA-F-]+@demo\.example\.com"
                        (:email first-profile)))
      (t/is (not= (:email first-profile) (:email second-profile))))))

(t/deftest create-demo-profile-requires-feature-flag
  (with-redefs [cf/flags (disj cf/flags :demo-users)]
    (let [{:keys [error]} (th/command! {::th/type :create-demo-profile})]
      (t/is (th/ex-of-code? error :demo-users-not-allowed)))))

(t/deftest create-demo-profile-keeps-onboarding-by-default
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [{:keys [error result]} (th/command! {::th/type :create-demo-profile})]
      (t/is (nil? error))
      (let [saved   (th/db-get :profile {:email (:email result)})
            decoded (profile/decode-row saved)]
        (t/is (nil? (get-in decoded [:props :onboarding-viewed])))))))

(t/deftest create-demo-profile-skips-onboarding-when-requested
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [{:keys [error result]} (th/command! {::th/type :create-demo-profile
                                               :skip-onboarding true})]
      (t/is (nil? error))
      (let [saved   (th/db-get :profile {:email (:email result)})
            decoded (profile/decode-row saved)]
        (t/is (true? (get-in decoded [:props :onboarding-viewed])))
        (t/is (= (:main cf/version)
                 (get-in decoded [:props :release-notes-viewed])))))))

(t/deftest create-demo-profile-explicit-false-keeps-onboarding
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [{:keys [error result]} (th/command! {::th/type :create-demo-profile
                                               :skip-onboarding false})]
      (t/is (nil? error))
      (let [saved   (th/db-get :profile {:email (:email result)})
            decoded (profile/decode-row saved)]
        (t/is (nil? (get-in decoded [:props :onboarding-viewed])))))))

(t/deftest create-demo-profile-rejects-non-boolean-skip-onboarding
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [{:keys [error]} (th/command! {::th/type :create-demo-profile
                                        :skip-onboarding "yes"})]
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :params-validation)))))

(t/deftest create-demo-profile-uses-global-delay-by-default
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [captured (atom nil)]
      (with-redefs [wrk/submit! (fn [& {:keys [::wrk/task ::wrk/delay]}]
                                  (reset! captured {:task task :delay delay}))]
        (let [{:keys [error result]} (th/command! {::th/type :create-demo-profile})]
          (t/is (nil? error))
          (t/is (some? (:email result)))
          (t/is (= :demo-purge (:task @captured)))
          (t/is (= (cf/get-deletion-delay) (:delay @captured))))))))

(t/deftest create-demo-profile-accepts-short-expires-in
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [captured (atom nil)]
      (with-redefs [wrk/submit! (fn [& {:keys [::wrk/task ::wrk/delay]}]
                                  (reset! captured {:task task :delay delay}))]
        (let [{:keys [error result]} (th/command! {::th/type :create-demo-profile
                                                   :expires-in "10m"})]
          (t/is (nil? error))
          (t/is (some? (:email result)))
          (t/is (= :demo-purge (:task @captured)))
          (t/is (= (ct/duration "10m") (:delay @captured))))))))

(t/deftest create-demo-profile-rejects-expires-in-below-minimum
  (with-redefs [cf/flags (conj cf/flags :demo-users)]
    (let [{:keys [error]} (th/command! {::th/type :create-demo-profile
                                        :expires-in "1m"})]
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :invalid-expires-in)))))

(t/deftest create-demo-profile-rejects-expires-in-above-global-delay
  (with-redefs [cf/flags (conj cf/flags :demo-users)
                cf/get-deletion-delay (fn [] (ct/duration {:days 7}))]
    (let [{:keys [error]} (th/command! {::th/type :create-demo-profile
                                        :expires-in "200h"})]
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :invalid-expires-in)))))
