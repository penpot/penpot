;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-demo-test
  (:require
   [app.auth :as auth]
   [app.config :as cf]
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
